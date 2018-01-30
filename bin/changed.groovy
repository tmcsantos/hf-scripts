#!/usr/bin/env groovy

@Grab(group='org.eclipse.jgit', module='org.eclipse.jgit', version='4.9.2.201712150930-r')
@Grab(group='org.apache.maven', module='maven-model-builder', version='3.3.9')
@Grab(group='org.slf4j', module='slf4j-nop', version='1.7.2')

import groovy.transform.EqualsAndHashCode
import org.apache.maven.model.Model
import org.apache.maven.model.Parent
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuilder
import org.apache.maven.model.building.ModelBuildingException
import org.apache.maven.model.building.ModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingResult
import org.apache.maven.model.building.ModelSource
import org.apache.maven.model.building.StringModelSource
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.CanonicalTreeParser

import java.nio.file.Path

import static org.eclipse.jgit.diff.DiffEntry.ChangeType.*

/*************************/
/** Input parse section **/
/*************************/

def cli = new CliBuilder(
    usage: 'changed [options] <repo> <oldCommit> [<newCommit>]',
    header: 'Options:')
cli._(longOpt: 'help', 'print this message')
cli.v(longOpt: 'verbose', 'show extra information')

options = cli.parse(args)

if (!options || options?.help || options?.arguments()?.size() < 2) {
  cli.usage()
  System.exit(0)
}

File repoDir = new File(options.arguments()[0])
if(!repoDir.exists()){
  println 'Repository directory does not exist!'
  System.exit(0)
}

String oldCommit = options.arguments()[1]
// default to HEAD if second commit is not provided
String newCommit = options.arguments().size() > 2 ? options.arguments()[2] : 'HEAD'

ChangeDetector changeDetector = new ChangeDetector(repoDir, oldCommit, newCommit)
changeDetector.verbose = options.v as boolean

changeDetector.findForMaven()

/***************************************************************************************/
/** classes bellow that might be reused if we decide to create a library of some sort **/
/***************************************************************************************/

/**
 * Main entry point for the functionality of this script
 */
class ChangeDetector {
  File repo
  String oldCommit
  String newCommit
  boolean verbose = false

  ChangeDetector(File repo, String oldCommit, String newCommit) {
    this.repo = repo
    this.oldCommit = oldCommit
    this.newCommit = newCommit
  }

  /**
   * Finds what modules changed grouped by parent for a Maven project
   * @return
   */
  Map findForMaven() {
    List changes = findChanges()

    if(verbose) {
      println '\nChanges Detected:'
      println '-' * 50
      changes.each { println it }
    }

    Set<File> poms = changes.collect { String changedFile ->
      findBuildFile(repo, new File(repo, changedFile), 'pom.xml')
    }

    if(verbose) {
      println '\nRelated build files:'
      println '-' * 50
      poms.each { println it.canonicalPath - (repo.canonicalPath + '/') }
    }

    Set<MavenModule> modules = poms.collect { File pom ->
      MavenModule.buildModule(pom)
    }

    Map<MavenModule, List<MavenModule>> modulesByProject = modules.groupBy { MavenModule m -> m.root }

    println '\nModules changed by project:'
    println '-' * 50
    modulesByProject.each { MavenModule parent, List<MavenModule> v ->
      println "${parent.pom.canonicalPath - (repo.canonicalPath + '/')} - ${parent.id}"
      v.each { MavenModule m ->
        println ' ' * 2 + "${m.path} - ${m.id}"
      }
    }

    if(verbose) {
      println '\nBuild Commands:'
      println '-' * 50
      modulesByProject.each { MavenModule parent, List<MavenModule> v ->
        List<String> command = []
        command << 'mvn'
        command << '-f'
        command << "'${parent.pom.canonicalPath - (repo.canonicalPath + '/')}'"
        command << '-pl'
        command << "'${v.collect { it.path }.join(',')}'"
        command << '-amd'
        command << 'clean'
        command << 'install'

        println command.join(' ')
      }
    }

    modulesByProject
  }

  Map findAnt() {
    // TODO
    [:]
  }

  /**
   * Returns the list changed files for the git repo between the given commits
   * @param repoDir
   * @param oldCommit
   * @param newCommit
   * @return
   */
  List<String> findChanges() {
    Git git = Git.open(repo)
    try {
      Repository repo = git.getRepository()

      ObjectReader reader = repo.newObjectReader()

      ObjectId oldTreeId = repo.resolve("${oldCommit}^{tree}")
      if(!oldTreeId){
        println "Unable to find commit [${oldCommit}] on the change tree!"
        System.exit(0)
      }

      ObjectId newTreeId = repo.resolve("${newCommit}^{tree}")
      if(!newTreeId){
        println "Unable to find commit [${oldCommit}] on the change tree!"
        System.exit(0)
      }

      git.diff()
          .setOldTree(new CanonicalTreeParser(null, reader, oldTreeId))
          .setNewTree(new CanonicalTreeParser(null, reader, newTreeId))
          .call()
          .collectMany { DiffEntry entry ->
        switch (entry.changeType) {
          case ADD:
            return [entry.newPath]
          case [RENAME, COPY]:
            return [entry.oldPath, entry.newPath]
          default:
            return [entry.oldPath]
        }
      }
    }
    finally { git.close() }
  }

  /**
   * Find the closest build file to the given file
   * @param base
   * @param current
   * @return
   */
  File findBuildFile(File base, File current, String buildFilename) {
    // Search the for the closest pom file to the given file
    // Location: a/pom.xml
    // Iterations: a/b/c/version.xml -> a/b/c/ -> a/b/ -> a/

    if (current.directory) {
      // Does the pom exist under current dir?
      File pom = new File(current, buildFilename)
      if (pom.exists()) {
        return pom
      }
    } else if (current.name == buildFilename && current.exists()) {
      // This is it!
      return current
    }

    if (base == current) {
      // We're done, give up.
      return null
    }

    // Keep searching up
    findBuildFile(base, current.parentFile, buildFilename)
  }
}

/**
 * Stores metadata for a maven module
 */
@EqualsAndHashCode(includes = ['id'])
class MavenModule {
  String id
  String path
  File pom
  int depth
  MavenModule parent

  private MavenModule(String id, File pom, MavenModule parent) {
    this.id = id
    this.pom = pom
    this.parent = parent
    this.depth = (parent == null ? 0 : parent.depth + 1)

    if (parent == null) {
      this.path = "."
    } else {
      File parentBaseDir = parent.pom.parentFile
      File moduleBaseDir = pom.parentFile
      this.path = parentBaseDir.toPath().normalize().relativize(moduleBaseDir.toPath().normalize())
    }
  }

  static MavenModule buildModule(Model model) throws IOException {
    String id = "${model.groupId}:${model.artifactId}"

    // find the parents recursively cause thats all we need
    if (model.parent) {
      File parentPom = new File(model.projectDirectory, model.parent.relativePath)

      // we only care about local poms
      if (parentPom.exists()) {
        Model parentModel = buildModel(parentPom)
        String foundParentId = "${parentModel.groupId}:${parentModel.artifactId}"
        String referedParentId = "${model.parent.groupId}:${model.parent.artifactId}"

        Path parentPath = parentPom.parentFile.toPath().normalize()
        Path modulePath = model.pomFile.parentFile.toPath().normalize()
        String module = parentPath.relativize(modulePath).toString()

        // is the parent pom we found the actual parent we're refering to?
        // and
        // does the parent know this module exists?
        if (foundParentId == referedParentId && parentModel.modules.contains(module)) {
          return new MavenModule(id, model.pomFile, buildModule(parentModel))
        }
      }
    }

    new MavenModule(id, model.pomFile, null)
  }

  static MavenModule buildModule(File pom) throws IOException {
    buildModule(buildModel(pom))
  }

  static Model buildModel(File pom) throws IOException {
    ModelBuilder builder = new DefaultModelBuilderFactory().newInstance()
    ModelBuildingResult result

    try {
      DefaultModelBuildingRequest request = new DefaultModelBuildingRequest()
          .setPomFile(pom)
          .setModelResolver(new OfflineModelResolver())
          .setSystemProperties(System.properties)
          .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)

      result = builder.build(request)
    } catch (ModelBuildingException e) {
      throw new IOException(e)
    }

    return result.effectiveModel
  }

  String getFullPath() {
    if (parent == null) {
      return "."
    }
    return (parent.getFullPath().equals(".") ? "" : parent.getFullPath() + File.separator) + path
  }

  MavenModule getRoot() {
    return (parent == null ? this : parent.getRoot())
  }

  @Override
  String toString() {
    "MavenModule{" +
        "id=" + id +
        ", path=" + path +
        ", pom=" + pom +
        ", parent=" + (parent == null ? "<none>" : parent.id) +
        '}'
  }
}

/**
 * Dumb model resolver that just returns a fake pom for the requested parent. This is used for external pom resolution
 * and we don't need to actually resolve it, at least for now.
 */
class OfflineModelResolver implements ModelResolver {

  @Override
  ModelSource resolveModel(String groupId, String artifactId, String version) throws UnresolvableModelException {
    new StringModelSource("""<?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
          <modelVersion>4.0.0</modelVersion>
          <groupId>${groupId}</groupId>
          <artifactId>${artifactId}</artifactId>
          <version>${version}</version>
          <packaging>pom</packaging>
        </project>
        """
    )
  }

  ModelSource resolveModel(Parent parent) {
    resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion())
  }

  void addRepository(org.apache.maven.model.Repository repository) {
    /* NO-OP */
  }

  void addRepository(org.apache.maven.model.Repository repository, boolean b) {
    /* NO-OP */
  }

  @Override
  ModelResolver newCopy() {
    new OfflineModelResolver()
  }
}
