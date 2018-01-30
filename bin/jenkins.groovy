#!/usr/bin/env groovy

//@Grab(group = 'com.offbytwo.jenkins', module = 'jenkins-client', version= '0.3.7')
//@Grab(group = 'org.bitbucket.mstrobel', module = 'procyon-compilertools', version = '0.5.32')

import com.offbytwo.jenkins.JenkinsServer
import com.offbytwo.jenkins.model.Build
import com.offbytwo.jenkins.model.BuildResult
import com.offbytwo.jenkins.model.BuildWithDetails
import com.offbytwo.jenkins.model.Job
import com.offbytwo.jenkins.model.JobWithDetails
import groovy.time.TimeCategory

JenkinsServer jenkins = new JenkinsServer(new URI("http://devci.pentaho.com/job/8.1-SNAPSHOT"))
File output = new File("report.csv")

if (output.exists()) output.delete()

output.withWriter { Writer writer ->
  writer.write(['JOB', 'LAST_STATUS', 'LAST_SUCCESS_DATE', 'LAST_SUCCESS_DURATION'].join(',') + System.lineSeparator())


  List durations
  jenkins.getJobs('builds').each { String name, Job job ->
    println name

    JobWithDetails jobDetails = job.details()
    BuildWithDetails lastDetails = jobDetails.getLastBuild().details()
    BuildWithDetails lastSuccessDetails = jobDetails.getLastSuccessfulBuild().details()

    durations = jobDetails.getAllBuilds().stream()
        .map { Build build ->
      build.details()
    }
    .filter { BuildWithDetails details ->
      details.getResult() == BuildResult.SUCCESS
    }
    .map { BuildWithDetails details ->
      details.duration
    }
    .collect()


    writer.write([
        name,
        lastDetails.result,
        new Date(lastSuccessDetails.timestamp).format('MM/dd/yyyy'),
        durations ? (durations.sum() / durations.size()) : 0
    ].join(',') + System.lineSeparator())
  }

  // do build flow separate

  Job buildFlow = jenkins.getJob('build-flow')
  JobWithDetails jobDetails = buildFlow.details()
  BuildWithDetails lastDetails = jobDetails.getLastBuild().details()
  BuildWithDetails lastSuccessDetails = jobDetails.getLastSuccessfulBuild().details()

  println "Full Stack"
  durations = jobDetails.getAllBuilds().stream()
    .map { Build build ->
      build.details()
    }
    .filter { BuildWithDetails details ->
      details.getResult() == BuildResult.SUCCESS && details.duration > 2 * 60 * 60 * 1000 // get only more than 2h, less is not real
    }
    .map { BuildWithDetails details ->
      details.duration
    }
    .collect()


  println "Min: ${TimeCategory.minus(new Date((long) durations.min()), new Date(0))}"
  println "Max: ${TimeCategory.minus(new Date((long) durations.max()), new Date(0))}"
  println "Avg: ${TimeCategory.minus(new Date((long) (durations.sum() / durations.size())), new Date(0))}"


  writer.write([
      buildFlow.name,
      lastDetails.result,
      new Date(lastSuccessDetails.timestamp).format('MM/dd/yyyy'),
      (durations.sum() / durations.size())
  ].join(',') + System.lineSeparator())


  println 'done.'
}
