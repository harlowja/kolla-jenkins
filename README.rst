==================
Kolla jenkins jobs
==================

This repo exists to serve as the one stop shop for
the cloudplatform jenkin job configuration and tools and
scripts and configuration to be able to recreate those jobs
on a new or existing set of jenkins masters (as well as to
host a location where we as a group can peer-review these
things).

How to use
----------

1. Clone (or clone a fork of) this repo.
2. Setup a virtualenv.
3. Enter that virtualenv.
4. Install (via pip) the requirements.txt file.
5. Determine your jenkins token and export it as ``JENKINS_TOKEN``
   for future usage.
6. Update job configuration...
7. Edit ``jenkins_jobs.ini`` as needed (for example to update
   the target jenkins master to be configured).
8. Test your change via a command like:

      ``jenkins-jobs --conf ./jenkins_jobs.ini --user $USER -p $JENKINS_TOKEN test projects/ -o output/``

9. Perform your change via a command like:

      ``jenkins-jobs --conf ./jenkins_jobs.ini -u $USER -p $JENKINS_TOKEN  update projects/``

Notes
`````

- You can enable debugging output by passing ``-l DEBUG``

Useful Links
````````````

1. https://www.mediawiki.org/wiki/Continuous_integration/Jenkins_job_builder
2. http://blogs.rdoproject.org/6006/manage-jenkins-jobs-with-yaml
