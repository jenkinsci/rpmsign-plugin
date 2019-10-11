# Jenkins RPM Sign Plugin

[![Build Status](https://ci.jenkins.io/buildStatus/icon?job=Plugins/rpmsign-plugin/master)](https://ci.jenkins.io/job/Plugins/job/rpmsign-plugin/job/master/)

This plugin adds a post-build step to sign rpms using GPG.

## Dependencies

This plugin depends on both **gpg** and **expect** being installed on the host machine.
Make sure your Locale is set to en_US, otherwise the expect script wont work as desired.

## Usage

### Configure RPM signing keys
* Go to Jenkins >> Manage Jenkins >> Configure System
* Go to "RPM Signing Keys" section
* Click on "Add GPG key" button to configure gpg key in jenkins master.

## Option-1: Using Jenkinsfile (or pipeline)
* Use Jenkins "Pipeline Syntax" (Snippet Generator) to help generate pipeline step.
* Select "rpmSign: [RPMSign] - Sign RPMs" from "Sample Step" drop down.
* Set values for "Sign RPMs"
    * "GPG Key" drop down to select value from the configured GPG keys (mentioned in above section).
    * "includes" textbox to provide rpm paths (default value is \*\*/target/*.rpm).
    * "Cmdline Options" for custom options to be passed to rpm command.
    * "Resign?" checkbox (enable it if resigning of rpm is required).
* Click on "Generate Pipeline Script"
* genereated step example: 
    ```
    rpmSign(rpms: [[gpgKeyName: '121ADA11', includes: 'build/distributions/*.rpm']]])
    ```
    
## Option-2: USing Jenkins job configuration
* Click on "Configure" button of jenkins job.
* Select "[RPMSign] - Sign RPMs" from "Post-build Action" drop down.
* Click on "Add RPM" button.
* Set values for GPG Key, includes, Cmdline Options, and Resign.
