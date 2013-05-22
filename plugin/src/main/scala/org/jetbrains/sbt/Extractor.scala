package org.jetbrains.sbt

import sbt.Keys._
import sbt._
import sbt.Load.BuildStructure
import sbt.Value
import Utilities._

/**
 * @author Pavel Fatin
 */
object Extractor {
  def extractStructure(state: State): StructureData = {
    val extracted = Project.extract(state)

    val structure = extracted.structure

    val projectRef = Project.current(state)

    val projectData = extractProject(state, structure, projectRef)

    val repositoryData = {
      val modulesData = extracted.structure.allProjectRefs
        .flatMap(extractModules(state, _)).distinctBy(_.id)

      RepositoryData(new File("."), modulesData)
    }

    StructureData(projectData, repositoryData)
  }

  def extractProject(state: State, structure: BuildStructure, projectRef: ProjectRef): ProjectData = {
    val name = Keys.name.in(projectRef, Compile).get(structure.data).get

    val organization = Keys.organization.in(projectRef, Compile).get(structure.data).get

    val version = Keys.version.in(projectRef, Compile).get(structure.data).get

    val base = Keys.baseDirectory.in(projectRef, Compile).get(structure.data).get

    val configurations = Seq(
      extractConfiguration(state, structure, projectRef, Compile),
      extractConfiguration(state, structure, projectRef, Test),
      extractConfiguration(state, structure, projectRef, Runtime))

    val java = {
      val home = Keys.javaHome.in(projectRef, Compile).get(structure.data).get

      val options: Seq[String] = Project.runTask(javacOptions.in(projectRef, Compile), state) match {
        case Some((_, Value(it))) => it
        case _ => Seq.empty
      }

      home.map(JavaData(_, options))
    }

    val scala: Option[ScalaData] = {
      val options: Seq[String] = Project.runTask(scalacOptions.in(projectRef, Compile), state) match {
        case Some((_, Value(it))) => it
        case _ => Seq.empty
      }

      Project.runTask(scalaInstance.in(projectRef, Compile), state) collect {
        case (_, Value(instance)) =>
          val extraJars = instance.extraJars.filter(_.getName.contains("reflect"))
          ScalaData(instance.version, instance.libraryJar, instance.compilerJar, extraJars, options)
      }
    }

    val project = Project.getProject(projectRef, structure).get

    val projects = project.aggregate.map(extractProject(state, structure, _))

    ProjectData(name, organization, version, base, configurations, java, scala, projects)
  }

  def extractConfiguration(state: State, structure: BuildStructure, projectRef: ProjectRef, configuration: Configuration): ConfigurationData = {
    val sources = Keys.unmanagedSourceDirectories.in(projectRef, configuration).get(structure.data).get

    val resources = Keys.unmanagedResourceDirectories.in(projectRef, configuration).get(structure.data).get

    val output = Keys.classDirectory.in(projectRef, configuration).get(structure.data).get

    val moduleDependencies = {
      val classpath: Option[Classpath] = Project.runTask(externalDependencyClasspath.in(projectRef, configuration), state) collect {
        case (_, Value(it)) => it
      }

      val moduleIDs = classpath.get.flatMap(_.get(Keys.moduleID.key))

      moduleIDs.map(it => ModuleIdentifier(it.organization, it.name, it.revision))
    }

    val projectDependencies = Project.getProject(projectRef, structure).get.dependencies.map(it => it.project.project)

    val jarDependencies: Seq[File] = {
      val classpath: Option[Classpath] = Project.runTask(unmanagedJars.in(projectRef, configuration), state) collect {
        case (_, Value(it)) => it
      }
      classpath.get.map(_.data)
    }

    ConfigurationData(configuration.name, sources, resources, output, projectDependencies, moduleDependencies, jarDependencies)
  }

  def extractModules(state: State, projectRef: ProjectRef): Seq[ModuleData] = {
    def run(task: TaskKey[UpdateReport]): Seq[ModuleReport] = {
      val updateReport: UpdateReport = Project.runTask(task.in(projectRef), state) collect {
        case (_, Value(it)) => it
      } getOrElse {
        throw new RuntimeException()
      }

      updateReport.configurations.flatMap(_.modules).filter(_.artifacts.nonEmpty)
    }

    val moduleReports = run(update) ++ run(updateClassifiers) //++ run(updateSbtClassifiers)

    merge(moduleReports)
  }

  private def merge(moduleReports: Seq[ModuleReport]): Seq[ModuleData] = {
    moduleReports.groupBy(_.module).toSeq.map { case (module, reports) =>
      val id = ModuleIdentifier(module.organization, module.name, module.revision)

      val allArtifacts = reports.flatMap(_.artifacts)

      def artifacts(kind: String) = allArtifacts.filter(_._1.`type` == kind).map(_._2).distinct

      ModuleData(id, artifacts("jar"), artifacts("doc"), artifacts("src"))
    }
  }
}
