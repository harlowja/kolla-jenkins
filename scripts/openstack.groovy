// openstack.groovy

stage("Loading remote libraries")
helpers = fileLoader.fromGit(
    "lib/helpers",
    "https://github.com/harlowja/kolla-jenkins.git",
    "master")

stage("Input parameter resolving")
params = [:]
node {
    helpers.clean_ws {
        helpers.smart_git(deploy_url, deploy_ref)
        // NOTE(harlowja): some of these are coming in via job parameters (so that
        // is why you will not find the variables defined anywhere, they are
        // apparently injected as globals automatically...)
        params.putAll(
            helpers.resolve_sources(
                load_yaml(readFile("envs/sources.yaml")), project,
                project_url=project_url, project_ref=project_ref,
                requirement_url=requirement_url, requirement_ref=requirement_ref,
                kolla_url=kolla_url, kolla_ref=kolla_ref))
    }
}
project_url = params["project_url"]
project_ref = params["project_ref"]
requirement_url = params["requirement_url"]
requirement_ref = params["requirement_ref"]
kolla_url = params["kolla_url"]
kolla_ref = params["kolla_ref"]
raw_run_details = [
    "Project": "${project}",
    "Project git": "${project_url}",
    "Project ref": "${project_ref}",
    "Deploy git": "${deploy_url}",
    "Deploy ref": "${deploy_ref}",
    "Docker repo (for push)": "${docker_repo}",
    "Requirement git": "${requirement_url}",
    "Requirement ref": "${requirement_ref}",
    "Unit test path": "${unit_test_path}",
    "Kolla git": "${kolla_url}",
    "Kolla ref": "${kolla_ref}",
    "Kolla image namespace": "${kolla_image_namespace}",
    "Maintainers": "${maintainers}",
]
run_details = pretty_format(raw_run_details)
raw_job_details = helpers.fetch_job_details().raw
job_details = pretty_format(raw_job_details)
start_msg = ""
start_msg += "```\n"
start_msg += job_details
start_msg += "```\n"
start_msg += "*Parameters:*\n"
start_msg += "```\n"
start_msg += run_details
start_msg += "```\n"

// GD specifics for our artifactory pypi url (may need to be removed or
// moved or other)
pypi_index_server = "artifactory.secureserver.net"
pypi_index_server_path = "artifactory/api/pypi/python-cloud-local/simple"

// GD specifics for docker (may need to be removed or
// moved or other)
docker_registry = "docker-cloud-ostack-local.artifactory.secureserver.net"
docker_registry_url = "https://${docker_registry}"

// Now really start...
node {
    started_at = helpers.start(start_msg)
}

// Clone out our various repos (so that kolla can absorb them).
stashes = [:]
stashes["${project}-patches"] = "${project}-patches.stash"
stashes["requirements-patches"] = "requirements-patches.stash"
repos = []
repos.add([name: 'kolla', ref: kolla_ref, url: kolla_url])
repos.add([name: 'clean', ref: project_ref, url: project_url])
repos.add([name: 'deploy', ref: deploy_ref, url: deploy_url])
repos.add([name: 'requirements', ref: requirement_ref, url: requirement_url])
helpers.slack_stage("Cloning (and stashing) ${repos.size()} repositories", true) {
    stashes.putAll(helpers.clone_many_and_stash(repos))
}

// Gather our patches and known failing tests (if any).
project_patches = []
project_skip_tests = []
helpers.slack_stage("Extracting project specific patches (and known skippable tests)", true) {
    extracted = helpers.extract_patches_and_skips(project, stashes["deploy"])
    project_patches.addAll(extracted.patches)
    project_skip_tests.addAll(extracted.skip_tests)
}
if(!project_patches.isEmpty()) {
    stashes['dirty'] = helpers.apply_patches(
        project, project_patches, stashes['deploy'],
        stashes["${project}-patches"], stashes['clean'])
}
else {
    stashes['dirty'] = stashes['clean']
}

// We *may* patch up the requirements repo (potentially) so we need to
// extract its patches as well (if any).
requirements_patches = []
helpers.slack_stage("Extracting requirement (repo) specific patches", true) {
    extracted = helpers.extract_patches_and_skips('requirements', stashes["deploy"])
    requirements_patches.addAll(extracted.patches)
}
if(!requirements_patches.isEmpty()) {
    stashes['requirements'] = helpers.apply_patches(
        "requirements", requirements_patches, stashes['deploy'],
        stashes["requirements-patches"], stashes['requirements'])
}

// Gather this; the information should be exactly what the docker image
// will contain (though it is using a venv to get it); other way of doing
// this could be to create a kolla image with a known name, then rename
// it later (but this should be good enough).
project_details = [:]
helpers.slack_stage("Extracting ${project} project details", true) {
    def tmp_stashes = stashes.clone()
    // Rename this due to backwards compat... (fix that...)
    tmp_stashes['constraints'] = tmp_stashes['requirements']
    node('build-slave-cent7') {
        project_details.putAll(
            helpers.introspect_py_project(project, venv_py_version,
                                          helpers.creds.pypi, pypi_index_server,
                                          pypi_index_server_path,
                                          tmp_stashes))
    }
}
helpers.send_quoted_slack_message(
    "Gathered project details", pretty_format(project_details))

// NOTE: this all needs to run on the same node, because once we switch
// nodes it will not have the same set of docker images, so unless we store
// the temporary (non-tested) images somewhere, we have to do this in one
// node block...
node('build-slave-cent7') {
    def curr_dir = pwd()
    helpers.slack_stage("Wiping existing docker images", true) {
        helpers.clean_docker_images()
    }
    ///
    ///
    /// Kolla begins now!
    ///
    ///
    def images = helpers.slack_stage("Building ${project} images using kolla", true) {
        def tmp_job_details = raw_run_details + raw_job_details
        tmp_job_details["Project details"] = project_details
        def stash_to_dir_names = [:]
        stash_to_dir_names['requirements'] = "requirements"
        stash_to_dir_names['dirty'] = "${project}"
        return helpers.build_kolla_images(
            project, project_details,
            maintainers, new Integer(env.BUILD_NUMBER),
            dump_json(tmp_job_details), stashes, stash_to_dir_names,
            kolla_image_namespace, venv_py_version,
            helpers.creds.pypi, pypi_index_server,
            pypi_index_server_path)
    }
    // TODO: avoid hard coding some of this (need a better way to determine
    // what kolla actually built and how to connect what we asked for with
    // what it did); via https://review.openstack.org/#/c/395273/ or something
    // like it...
    def test_image_repo = "${kolla_image_namespace}/centos-source-${project}-base"
    def test_image = null
    for(image in images) {
        if(image.repo == test_image_repo) {
            test_image = image
        }
    }
    // Start testing that image.
    if(test_image == null) {
        // Err, what was built??
        throw new RuntimeException(
            "No image found with repo named '${test_image_repo}'")
    }
    def reports_dir = "${curr_dir}/reports"
    def test_image_name = "${test_image.repo}:${test_image.tag}"
    // Wipe the reports directory (incase we are in a already used
    // workspace)...
    sh """
    rm -rf ${reports_dir}
    mkdir ${reports_dir}
    """
    stage("(Unit) testing ${project} image")
    def stage_name_pretty = "(Unit) testing ${project} image `${test_image_name}`"
    stage_name_pretty += " (${project_skip_tests.size()} skips)"
    helpers.slack_stage(stage_name_pretty, false) {
        def tmp_test_image = docker.image(test_image_name)
        tmp_test_image.inside() {
            // All of this magically runs inside the image, sweet!
            helpers.run_and_publish_tests(project, project_skip_tests,
                                          unit_test_path, unit_test_timeout,
                                          reports_dir, "Unit tests")
        }
    }
    stage("Uploading ${project} images")
    if(docker_repo) {
        helpers.slack_stage("Uploading ${project} images to ${docker_registry}", false) {
            docker.withRegistry(docker_registry_url, helpers.creds.docker.registry) {
                for(image in images) {
                    // Avoid uploading things like centos or other stuff that
                    // we didn't actually build...
                    if(image.repo.startsWith(docker_repo)) {
                        def tmp_image_name = "${image.repo}:${image.tag}"
                        helpers.named_substep("Uploading `${tmp_image_name}`") {
                            def tmp_image = docker.image(tmp_image_name)
                            tmp_image.push()
                        }
                    }
                }
            }
        }
    }
}

stage("Finished")
helpers.finish(started_at)

// openstack.groovy
