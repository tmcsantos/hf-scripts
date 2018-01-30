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

JenkinsServer jenkins = new JenkinsServer(new URI("http://wingman.pentaho.com:8080/"))
def job = jenkins.getJob('wingman')

long since = Date.parse('dd.MM.yyyy HH:mm:ss',"30.11.2017 00:00:00").time


Set toBuild = []
job.allBuilds.each { Build build ->
  BuildWithDetails details = build.details()
  BuildResult result = details.result

  if(!details.displayName || !details.displayName.contains(':')) return

  if(result == BuildResult.FAILURE && details.timestamp > since){

   toBuild << details.parameters
  }

}

toBuild.each { Map data ->
  println data
  job.build(data)
}

println "Triggered builds on ${toBuild.size()} pull requests"

