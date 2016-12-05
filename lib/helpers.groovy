def version = '1.0'

// Slack emojis and message prefix and postfix (that connect it to the
// job being ran).
emojis = [
    'happy': [":good_job:", ":success:", ":happygoat:", ":fundog:",
              ":smileycat:", ":pbjtime:"],
    'sad': [":face_palm:", ":failed:",
            ":rage:", ":sad-panda:", ":angry:", ":jenkinssick:",
            ":sadtroll:"],
]
msg_prefix = "Job '${env.JOB_NAME}' - #${env.BUILD_NUMBER}"
msg_postfix = " (<${env.BUILD_URL}|Open>)"

// Where the epel rpms are (for usage by dockerfiles...)
epel = [
    cent6: "http://mirror.centos.org/centos-6/6/extras/x86_64/Packages/epel-release-6-8.noarch.rpm",
    cent7: "http://mirror.centos.org/centos-7/7/extras/x86_64/Packages/epel-release-7-6.noarch.rpm",
]

// Credential names to there uuids (in jenkins) for usage by various
// other scripts (so that those scripts can just reference the nicer names).
creds = [
    // For a custom pypi index server.
    pypi: "",
    // Other useful creds for various other scripts.
    artifactory: [
        rw: "",
        ro: "",
    ],
    docker: [
        registry: "",
    ],
    jenkins: "76f3df84-a227-4e52-a339-6fddf483c911",
]

public class TestsFailed extends RuntimeException implements Serializable {
    public static long serialVersionUID = 1225867133L

    public TestsFailed(String what) {
        super(what)
    }
}

public class BorkedTests extends RuntimeException implements Serializable {
    public static long serialVersionUID = 2544979569L

    public BorkedTests(String what) {
        super(what)
    }
}

/**
 * Applies a bunch of patches to a project and stashes it away under a new stash.
 */
def apply_patches(String project, List patches,
                  String deploy_stash,
                  String patch_stash,
                  String clean_stash,
                  String slave_type='build-slave-cent7') {
    // Stash and then apply the patches (and stash the now 'dirty' project away)
    slack_stage("Stashing ${patches.size()} patches for ${project}", true) {
        node {
            clean_ws {
                unstash(deploy_stash)
                dir("patches/${project}") {
                    stash(name: patch_stash,
                          useDefaultExcludes: false,
                          includes: '**/*')
                }
            }
        }
    }
    def dirty_stash = clean_stash + ".dirty"
    slack_stage("Applying ${patches.size()} specific patches for ${project}", true) {
        // This one currently requires a slave node that isn't the puppet ones,
        // since those don't have git setup correctly, TODO, fix that...
        node(slave_type) {
            clean_ws {
                unstash(clean_stash)
                dir(".patches") {
                    unstash(patch_stash)
                }
                for(path in patches) {
                    named_sh("Applying patch `${path}`",
                    """
                        cd .patches
                        cat ${path}
                        git am < ${path}
                    """)
                }
                stash(name: dirty_stash,
                      useDefaultExcludes: false,
                      includes: '**/*')
            }
        }
    }
    return dirty_stash
}

/**
 * Returns a useful map with details (pretty and raw) about the current job.
 */
def fetch_job_details() {
    def lookups = [
        [name: 'build id', key: 'BUILD_ID'],
        [name: 'build name', key: 'JOB_NAME'],
        [name: 'build number', key: 'BUILD_NUMBER'],
        [name: 'build tag', key: 'BUILD_TAG'],
        [name: 'build url', key: 'BUILD_URL'],
    ]
    def raw_entries = [:]
    def lines = []
    for(int i = 0; i < lookups.size(); i++) {
        def entry_name = lookups[i].name
        def entry_key = lookups[i].key
        def entry_val = env.getProperty(entry_key)
        if(entry_val != null) {
            raw_entries["Job ${entry_name}"] = "${entry_val}"
            lines.add("Job ${entry_name} = ${entry_val}")
        }
    }
    return [raw: raw_entries, pretty: lines.join("\n")]
}

/**
 * Sends a pretty formatted slack *quoted* message with given body.
 */
def send_quoted_slack_message(String title,
                              String body, Boolean add_prefix=true,
                              String color=null) {
    def msg = ""
    if(add_prefix) {
        msg += msg_prefix + " "
    }
    msg += "*${title}:*\n"
    msg += "```\n"
    msg += body + "\n"
    msg += "```\n"
    slackSend(message: msg, failOnError: false, color: color)
}

/**
 * Creates a new workspace and ensures it is clean before further work...
 */
def clean_ws(Closure body) {
    ws {
        deleteDir()
        body.call()
    }
}

/**
 * Parses a test list (strips out comments + empty lines) and returns listing.
 */
def parse_test_list(String blob) {
    def ok_entries = []
    def lines = blob.split("\n")
    for(int i = 0; i < lines.size(); i++) {
        def line = lines[i]
        line = line.trim()
        if(line.isEmpty()) {
            continue
        }
        def line_pieces = line.split("#")
        if(line_pieces.length == 0) {
            continue
        }
        line = line_pieces[0]
        line = line.trim()
        if(!line.isEmpty()) {
            ok_entries.add(line)
        }
    }
    return ok_entries
}


/**
 * Writes a file (but also shows its contents to console for DEBUG/other).
 */
def pretty_write_file(String filename, String contents) {
    // Write a file + show it (to the jenkins log).
    def content_len = contents.size()
    def lines = [
        "Writing ${content_len} chars to ${filename}:",
        contents,
    ]
    println(lines.join("\n"))
    writeFile(file:filename, text:contents)
}

/**
 * Wipes *all* current docker images off the local system.
 *
 * This is useful for ensure a clean environment (before doing further
 * image building or other docker work).
 */
def clean_docker_images() {
    // This is installed via the slave_builder.sh so it may not
    // exist everywhere this is called (only currently on our slaves).
    sh "/usr/bin/wipe_docker_images"
}

/**
 * Writes a pip configuration file to a named file/path.
 */
def write_pip_conf(String target_path, String pypi_cred_id,
                   String pypi_index_server, String pypi_index_server_path) {
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: pypi_cred_id,
                      usernameVariable: 'PYPI_USER', passwordVariable: 'PYPI_PASS']]) {
        def pypi_index_url = "https://${PYPI_USER}:${PYPI_PASS}@${pypi_index_server}"
        if(pypi_index_server_path) {
            pypi_index_url += "/" + pypi_index_server_path
        }
        def pip_conf = """
[global]
# Prefer our over the pypi (public) url.
index-url = ${pypi_index_url}
extra-index-url = https://pypi.python.org/simple
"""
        pretty_write_file(target_path, pip_conf)
    }
}

/**
 * Introspects a python project (returns various information from its setup.py)
 */
def introspect_py_project(String project,
                          String venv_py_version,
                          String pypi_cred_id,
                          String pypi_index_server,
                          String pypi_index_server_path, Map stashes) {
    def project_details = [:]
    def base = "centos:7"
    // Various other packages *typically* needed for building python things.
    def extra_yum = """libffi-devel \
                       openssl-devel mysql-devel \
                       postgresql-devel libxml2-devel \
                       libxslt-devel openldap-devel"""
    // Only bother sucking in the large libvirt when doing something
    // with nova.
    if('nova' in project) {
        extra_yum += " libvirt-devel "
    }
    def tmp_dockerfile = """
    FROM ${base}

    COPY pip.conf /etc/

    RUN yum -y install ${epel.cent7}

    RUN yum clean all

    RUN yum -y install make gcc git

    RUN yum -y install python-pip python-virtualenv python-devel ${extra_yum}
    """
    clean_docker_images()
    clean_ws {
        unstash(stashes['dirty'])
        dir(".requirements") {
            unstash(stashes['constraints'])
        }
        pretty_write_file("Dockerfile", tmp_dockerfile)
        write_pip_conf("pip.conf",
                       pypi_cred_id, pypi_index_server,
                       pypi_index_server_path)
        def img = named_substep("Building a docker ${base} image") {
            return docker.build("${project}/introspection", ".")
        }
        img.inside() {
            named_sh("Create a venv inside that image",
                "virtualenv --python=${venv_py_version} .venv")
            named_sh("Setting up the venv inside that image",
            """
                set +x
                source .venv/bin/activate
                # The built-in versions of these are usually so old that
                # they just will not work so force them to be upgraded...
                pip install setuptools pip --upgrade
            """)
            named_sh("Installing ${project} inside the venv in that image",
            """
                set +x
                source .venv/bin/activate
                pip install -c .requirements/upper-constraints.txt .
            """)
            named_substep("Analyzing the venv inside that image") {
                // TODO: maybe extract this all in one go, though we'll
                // need a tool or other library to do it correctly...
                for(item in ["version", "name", "author", "fullname", "url"]) {
                    def info = sh(script: """
                        set +x
                        source .venv/bin/activate
                        python setup.py --${item}
                    """, returnStdout: true)
                    project_details[item] = info.trim()
                }
            }
        }
        def sha = sh(script:"set +x; git log --oneline -1 --pretty=format:%H", returnStdout: true)
        sha = sha.trim()
        project_details['sha'] = sha
        def old_version = project_details['version']
        // Rip off the devXYZ version and replace it with the sha
        def p = ~/^(.*?)[\.]?(dev\d+)$/
        def m = old_version =~ p
        if(m.matches()) {
            def base_version = m.group(1)
            // Typically 40 chars, but for us, 12 should guarantee
            // uniqueness for quite a long time (the linux kernel
            // bumped there uniqueness up to 12 recently, openstack
            // isn't quite yet the linux kernel...)
            //
            // This more mirrors what PBR used to do...
            def better_version = base_version + ".g" + sha.substring(0, 12)
            project_details['better_version'] = better_version
        }
        else {
            // Guess it wasn't a dev version in the first place...
            project_details['better_version'] = old_version
        }
    }
    return project_details
}

/**
 * Cleans given docker tag according to *basic* docker standards.
 */
def sanitize_docker_tag(String tag) {
    // See: https://github.com/docker/docker/issues/8445
    // and https://docs.docker.com/engine/reference/commandline/tag/
    def cleaned_tag = tag.toLowerCase()
    cleaned_tag = cleaned_tag.replace("/", "-")
    cleaned_tag = cleaned_tag.replace(" ", "_")
    return cleaned_tag
}

/**
 * Runs kolla image building (needs to be ran in a node context).
 */
def build_kolla_images(String project, Map project_details,
                       String maintainers,
                       Integer job_build_num, String job_details_json,
                       Map stashes, Map stash_to_dir_names,
                       String kolla_image_namespace,
                       String kolla_venv_py_version,
                       String pypi_cred_id,
                       String pypi_index_server,
                       String pypi_index_server_path) {
    def images_built = []
    def docker_image_tag = project
    docker_image_tag += "." + project_details["better_version"]
    docker_image_tag += ".j" + job_build_num
    docker_image_tag = sanitize_docker_tag(docker_image_tag)
    clean_ws {
        def curr_dir = pwd()
        // See:
        //
        // http://docs.openstack.org/developer/kolla/image-building.html
        def kolla_build_conf = """
[DEFAULT]
maintainer = ${maintainers}

[${project}-base]
type = local
location = ${curr_dir}/src/${project}

[openstack-base]
type = local
location = ${curr_dir}/src/requirements

[openstack-base-plugin-pip]
type = local
location = ${curr_dir}/src/pip

[${project}-base-plugin-jenkins]
type = local
location = ${curr_dir}/src/jenkins
"""
        // We don't want a bunch of the built in things to be installed
        // and/or we are fine with not installing them so just override
        // them so kolla won't do anything.
        def kolla_template_overrides = """
{% extends parent_template %}

{% set base_yum_repo_files_override = [] %}
{% set base_yum_url_packages_override = [] %}
{% set base_yum_repo_keys_override = [] %}
{% set base_yum_centos_repo_keys_override = [] %}
{% set base_yum_centos_repo_packages_override = [] %}

{% set base_centos_source_packages_append = ["nano"] %}

{% set openstack_base_pip_packages_override = [] %}

{% block openstack_base_header %}
ADD plugins-archive /
RUN cp /plugins/pip/pip.conf /etc/pip.conf
ENV PIP_CONFIG_FILE /etc/pip.conf
# ENV PIP_VERBOSE 1
{% endblock %}

{% block openstack_base_footer %}
{% endblock %}

{% block ${project}_base_footer %}
ADD plugins-archive /
RUN cp /plugins/jenkins/job.json /job.json
RUN /var/lib/kolla/venv/bin/pip --no-cache-dir \
        install --upgrade -c requirements/upper-constraints.txt \
        -r ${project}/test-requirements.txt \
        -r ${project}/requirements.txt
RUN /var/lib/kolla/venv/bin/pip --no-cache-dir \
        install os-testr -c requirements/upper-constraints.txt
{% endblock %}
"""
        // First checkout the constraints and code
        // then extract our kolla stash, then write out the needed
        // files to tell kolla to use the constraints and
        // code and then have it build the images.
        dir("src") {
            // Why a new array list u ask,
            // https://github.com/jenkinsci/workflow-cps-plugin/blob/master/README.md#known-limitations
            // https://issues.jenkins-ci.org/browse/JENKINS-27421 (and
            // a few more like it)
            for(stash in new ArrayList(stash_to_dir_names.keySet())) {
                def dir_name = stash_to_dir_names[stash]
                dir(dir_name) {
                    unstash(stashes[stash])
                }
            }
            // Save a breadcrumb trail so we know how this project
            // got created (and from where).
            dir("jenkins") {
                pretty_write_file("job.json", job_details_json)
            }
            // Save our customized pip conf so that pip installs can later use it.
            dir("pip") {
                write_pip_conf("pip.conf", pypi_cred_id,
                               pypi_index_server, pypi_index_server_path)
            }
        }
        dir("kolla") {
            unstash(stashes["kolla"])
            named_sh(
                "Kolla virtualenv creation",
                """
                virtualenv --python=${kolla_venv_py_version} .venv
                """
            )
            named_sh(
                "Kolla virtualenv population",
                """
                source .venv/bin/activate
                pip install pip setuptools --upgrade
                pip install .
                # Useful for debugging what got installed...
                pip freeze
                """
            )
            named_sh(
                "Kolla dependency graph creation/output",
                """
                source .venv/bin/activate
                pip install graphviz
                kolla-build --save-dependency ${project}.dot ${project}
                """
            )
            def dot_blob = readFile("${project}.dot")
            send_quoted_slack_message("Build dependency graph", dot_blob.trim())
            pretty_write_file("template-overrides.j2", kolla_template_overrides)
            pretty_write_file("kolla-build.conf", kolla_build_conf)
            named_sh(
                "Kolla image building",
                """
                source .venv/bin/activate
                kolla-build --config-file kolla-build.conf \
                            --namespace ${kolla_image_namespace} \
                            -t source \
                            --debug \
                            --tag ${docker_image_tag} \
                            --template-override template-overrides.j2 \
                            ${project}
                """
            )
        }
        def made_images = sh(script: 'docker images --format "{{.ID}}\\t{{.Repository}}\\t{{.Tag}}\\t{{.Size}}"', returnStdout: true)
        made_images = made_images.trim()
        // Using the option to make a table via the above CLI seems to not
        // retain the tabs, so just do it ourselves...
        def made_header = "IMAGE ID\tREPOSITORY\tTAG\tSIZE\n"
        send_quoted_slack_message("Images built/used", made_header + made_images)
        for(line in made_images.split("\n")) {
            line = line.trim()
            def pieces = line.split("\t")
            def id = pieces[0]
            def repo = pieces[1]
            def tag = pieces[2]
            def size = pieces[3]
            def image = [:]
            image["repo"] = repo
            image["tag"] = tag
            image["id"] = id
            image["size"] = size
            images_built.add(image)
        }
    }
    return images_built
}

/**
 * Examines source dictionary and assigns null/empty params to not be empty.
 */
def resolve_sources(sources, project,
                    project_url=null, project_ref=null,
                    requirement_url=null, requirement_ref=null,
                    kolla_url=null, kolla_ref=null) {
    def out_params = [:]
    out_params['project_url'] = project_url
    out_params['project_ref'] = project_ref
    out_params['requirement_url'] = requirement_url
    out_params['requirement_ref'] = requirement_ref
    out_params['kolla_url'] = kolla_url
    out_params['kolla_ref'] = kolla_ref
    def needed = []
    if(!project_url) {
        needed.add([project, 'url', 'project_url'])
    }
    if(!project_ref) {
        needed.add([project, 'ref', 'project_ref'])
    }
    if(!requirement_url) {
        needed.add(["requirements", 'url', 'requirement_url'])
    }
    if(!requirement_ref) {
        needed.add(["requirements", 'ref', 'requirement_ref'])
    }
    if(!kolla_url) {
        needed.add(["kolla", 'url', 'kolla_url'])
    }
    if(!kolla_ref) {
        needed.add(["kolla", 'ref', 'kolla_ref'])
    }
    def source = sources.get('source', [:])
    for(entry in needed) {
        def root_key = entry[0]
        def sub_key = entry[1]
        def out_key = entry[2]
        def root_val = source[root_key]
        // TODO: better exception we can raise?
        if(!root_val) {
            throw new RuntimeException(
                "Unable to get key value from source/${root_key} via provided sources")
        }
        def sub_val = root_val[sub_key]
        if(!sub_val) {
            throw new RuntimeException(
                "Unable to get key value from source/${root_key}/${sub_key} via provided sources")
        }
        out_params[out_key] = sub_val
    }
    return out_params
}

/**
 * A modified git that will work better for what we are using it for.
 */
def smart_git(String git_url, String git_ref,
              Integer clone_timeout=600) {
    // TODO(harlowja): We have to do this <hack> until the
    // following is resolved (we need a way to checkout tags or
    // various sha refs...)...
    //
    // https://issues.jenkins-ci.org/browse/JENKINS-37050
    timeout(time: clone_timeout, unit: 'SECONDS') {
        git(url: git_url, changelog: false, poll: false)
        sh """
            set +x
            echo "Checking out ref '${git_ref}'"
            git checkout "${git_ref}"
        """
    }
    detail_git()
}

/**
 * Run a bunch of (testr) backed tests and publish them somewhere.
 */
def run_and_publish_tests(String project, List project_skip_tests,
                          String unit_test_path, Integer unit_test_timeout,
                          String reports_dir, String report_name,
                          String kolla_venv_dir="/var/lib/kolla/venv/") {
    def sh_header = """
set +x
source ${kolla_venv_dir}/bin/activate
set -x
export OS_STDOUT_CAPTURE=1
export OS_STDERR_CAPTURE=1
export LANGUAGE=en_US
export LC_ALL=en_US.utf-8
export OS_TEST_PATH=${unit_test_path}
export OS_TEST_TIMEOUT=${unit_test_timeout}
# This is done so that python `getpass.getuser()` detects this user as the
# user to use (which kolla adds/sets up); otherwise python will
# blow up when trying to find the running users UID or USERNAME...
export USERNAME=${project}
cd /${project}
    """
    // We deal with the fail ourselves (carefully) post-test
    // running attempt...
    def sh_footer = """
exit 0
"""
    named_sh("Test initialization",
"""
${sh_header}
testr init
            """)
    def list_rc = named_substep("Listing tests",
        {
            def list_script = """
${sh_header}
set +e
testr list-tests > ${reports_dir}/all_tests.txt
rc=\$?
if [ "\$rc" != "0" ]; then
    # For some reason testr does not use stderr for failures?
    mv ${reports_dir}/all_tests.txt ${reports_dir}/list_failures.txt
fi
exit \$rc
"""
            return sh(script: list_script, returnStatus: true)
        }
    )
    if(list_rc != 0) {
        sh """
        # Show what the heck happened (if we can).
        cat ${reports_dir}/list_failures.txt
        """
        throw new BorkedTests(
            "Test listing failed with exit code ${list_rc}")
    }
    def ok_tests = []
    named_substep("Filtering tests") {
        def all_tests = readFile("${reports_dir}/all_tests.txt")
        for(test_name in all_tests.split("\n")) {
            if(test_name in project_skip_tests) {
                continue
            }
            // This skips/fixes the following (which aren't tests)...
            //
            // running=OS_STDOUT_CAPTURE=${OS_STDOUT_CAPTURE:-1} \
            // OS_STDERR_CAPTURE=${OS_STDERR_CAPTURE:-1} \
            // OS_TEST_TIMEOUT=${OS_TEST_TIMEOUT:-160} \
            // ${PYTHON:-python} -m subunit.run discover -t ./ ${OS_TEST_PATH:-./glance/tests} --list
            //
            // TODO: is there a bug against testr to not output that crap...
            if(test_name.startsWith(project)) {
                ok_tests.add(test_name)
            }
        }
        def usable_tests = ok_tests.join("\n")
        writeFile(file: "${reports_dir}/tests.txt", text: usable_tests)
        def skipped_tests = project_skip_tests.join("\n")
        writeFile(file: "${reports_dir}/skipped_tests.txt", text: skipped_tests)
    }
    named_sh("Running ${ok_tests.size()} tests (restricted to `${unit_test_path}` path)",
"""
${sh_header}
set +e
testr run \
    --parallel \
    --load-list ${reports_dir}/tests.txt \
    --subunit | subunit-trace -f
${sh_footer}
""")
    named_sh("Capturing & analyzing test results",
"""
${sh_header}
set +e
testr failing > ${reports_dir}/fails.txt
testr failing --list > ${reports_dir}/fails_list.txt
testr last --subunit > ${reports_dir}/results.subunit
cd ${reports_dir}
subunit2html results.subunit results.html
cat results.subunit | subunit-trace -n > results.txt
${sh_footer}
""")
    publishHTML(target: [allowMissing: true,
                         alwaysLinkToLastBuild: false,
                         keepAll: true,
                         reportDir: reports_dir,
                         reportFiles: "*.*",
                         reportName: report_name])
    def fails = []
    def found_fail_list = false
    if(fileExists("${reports_dir}/fails_list.txt")) {
        found_fail_list = true
        def fail_list = readFile("${reports_dir}/fails_list.txt")
        for(line in fail_list.split("\n")) {
            line = line.trim()
            if(line.isEmpty()) {
                continue
            }
            fails.add(line)
        }
    }
    if(fileExists("${reports_dir}/results.txt")) {
        def result_summary = extract_testr_summary(readFile("${reports_dir}/results.txt"))
        def msg_color = "good"
        def msg_prefix = "Passing"
        if(!fails.isEmpty()) {
            msg_color = "danger"
            msg_prefix = "Failed"
        }
        send_quoted_slack_message(
            "${msg_prefix} test report", result_summary, true, msg_color)
    }
    if(!found_fail_list) {
        throw new BorkedTests(
            "Did not find fail listing (which should exist on pass or failure")
    }
    if(!fails.isEmpty()) {
        throw new TestsFailed("${fails.size()} tests failed")
    }
}

/**
 * Clone some repo and stash it under given stash name.
 */
def clone_and_stash(String git_url, String git_ref,
                    String stash_name, String stash_includes='**/*') {
    node {
        clean_ws {
            smart_git(git_url, git_ref)
            stash(name: stash_name,
                  useDefaultExcludes: false, includes: stash_includes)
        }
    }
}

/**
 * Extracts patch list and skip test list from deploy repo stash.
 */
def extract_patches_and_skips(String project, String deploy_stash,
                              String patch_dir="patches") {
    def skip_tests = []
    def patches = []
    node {
        clean_ws {
            unstash(deploy_stash)
            sh """
                mkdir -p -v "${patch_dir}/${project}"
                mkdir -p -v "${patch_dir}/${project}/testlist"
            """
            def project_listing = sh(script:
                """set +x
                   cd ${patch_dir}/${project}
                   ls
                """, returnStdout: true)
            project_listing = project_listing.trim()
            def project_listing_lines = project_listing.split("\n")
            for(int i = 0; i < project_listing_lines.size(); i++) {
                def path = project_listing_lines[i]
                if(path.endsWith(".patch")) {
                    patches.add(path)
                }
            }
            def project_bad_test_listing = sh(script:
            """set +x
               cd ${patch_dir}/${project}/testlist
               ls
            """, returnStdout: true)
            project_bad_test_listing = project_bad_test_listing.trim()
            // Why using this iteration, well another weird jenkins + groovy bug...
            // Seems doing otherwise causes:
            //
            // java.io.NotSerializableException: java.util.AbstractList$Itr
            //
            // Arg...
            def project_bad_test_listing_lines = project_bad_test_listing.split("\n")
            for(int i = 0; i < project_bad_test_listing_lines.size(); i++) {
                def path = project_bad_test_listing_lines[i]
                if(path.endsWith("knownfailures.list")) {
                    def full_path = "${patch_dir}/${project}/testlist/${path}"
                    def entries = parse_test_list(readFile(full_path))
                    skip_tests.addAll(entries)
                }
            }
        }
    }
    return [skip_tests: skip_tests, patches: patches]
}

/**
 * Clone *many* repos and stash them.
 */
def clone_many_and_stash(List repos) {
    def stashes = [:]
    for(Map repo in repos) {
        def git_url = repo.url
        def git_ref = repo.ref
        def stash_name = repo.name + " " + git_ref + ".stash"
        // Jenkins has some stash naming conventions that will blow up if
        // we don't respect them (seems like it doesn't want stashes to look
        // like directories or be weirdly named to cause potential issues
        // on linux...)
        stash_name = stash_name.replace("/", "-")
        stash_name = stash_name.replace(" ", "_")
        named_substep("Cloning ${git_url} at reference `${git_ref}`") {
            clone_and_stash(git_url, git_ref, stash_name)
        }
        stashes[repo.name] = stash_name
    }
    return stashes
}

/**
 * Extracts and formats the useful data from a testr run result.
 */
def extract_testr_summary(String results) {
    // Janky but it will work...
    end_idx = -1
    start_idx = -1
    finders = [:]
    for(c in ["Totals", "Worker Balance"]) {
        c_pre_post = "=" * c.length()
        c_blob = c_pre_post + "\n"
        c_blob += c + "\n"
        c_blob += c_pre_post
        finders[c] = c_blob
    }
    start_idx = results.indexOf(finders["Totals"])
    if(start_idx != -1) {
        start_idx += finders["Totals"].length()
    }
    end_idx = results.lastIndexOf(finders["Worker Balance"])
    if(end_idx > 0) {
        end_idx -= 1
    }
    if(end_idx != -1 && start_idx != -1) {
        results = results.substring(start_idx, end_idx)
        results = results.trim()
    }
    return results
}

/**
 * Runs a named arbitrary substep.
 */
def named_substep(String sub_step, Closure body) {
    def msg = msg_prefix + " *starting* substep '${sub_step}', please wait..."
    msg += msg_postfix
    slackSend(message: msg, failOnError: false)
    return fail_to_slack(body)
}

/**
 * Runs a named shell substep.
 */
def named_sh(String sub_step, String sh_blob) {
    named_substep(sub_step) {
        sh(sh_blob)
    }
}

/**
 * Runs a script (in some context) that outputs details about git `cwd` repo.
 */
def detail_git() {
    sh """
        # This is by default on, we don't want it on for this script.
        set +x
        if [ -d ".git" ]; then
            echo "Git details"
            echo "==========="
            echo
            echo "== Remote URL: `git remote -v`"
            echo
            echo "== Remote Branches: "
            git branch -r
            echo
            echo "== Local Branches:"
            git branch
            echo
            echo "== Configuration (.git/config)"
            cat .git/config
            echo
            echo "== Most Recent 3 Commits"
            git log --max-count=3
            echo
            echo "== Uncommitted Changes"
            git diff
            echo
            echo "== Directory status"
            git status
            echo
          else
            echo "Not a git repository."
          fi
    """
}

/**
 * Returns a function that will run a tox environment on a given node type.
 *
 * @param tox_name name of the tox env to run with
 *
 * @param node_type the slave node type to use
 *
 * @param followup closure to execute on
 *                 the node (post tox running *successfully*)
 */
def make_tox_tester(String tox_name, String node_type,
                    Closure followup=null) {
    return {
        node (node_type){
            clean_ws {
                checkout scm
                sh """
                tox -e${tox_name} -v
                """
                if(followup != null) {
                    followup()
                }
            }
        }
    }
}

/**
 * Perform a stage and notify about it starting and finishing on slack.
 *
 * @param stage_name name of the stage
 *
 * @param start_stage start a jenkins stage before running main body closure
 *
 * @param body closure to execute
 */
def slack_stage(String stage_name, Boolean start_stage, Closure body=null) {
    def started_at = System.currentTimeMillis()
    def msg = msg_prefix + " *starting* step '${stage_name}'"
    msg += ", please wait..."
    msg += msg_postfix
    slackSend(color: 'normal', message: msg, failOnError: false)
    return fail_to_slack({
        def result = null
        if(start_stage) {
            stage(stage_name) {
                if(body != null) {
                    result = body.call()
                }
            }
        }
        else {
            if(body != null) {
                result = body.call()
            }
        }
        // It should have passed if it got here.
        def finished_at = System.currentTimeMillis()
        def duration = (finished_at - started_at) / 1000.0
        msg = msg_prefix + " *finished* step '${stage_name}' "
        msg += " after " + String.format("%.3f", duration / 60.0) + " minutes "
        def emoji_choices = emojis['happy']
        def rnd = new Random()
        msg += emoji_choices[rnd.nextInt(emoji_choices.size())]
        msg += " " + msg_postfix
        slackSend(color: 'good', message: msg, failOnError: false)
        return result
    })
}

/**
 * Finish a job, notifying its being finished (happily) in slack.
 */
def finish(start_ts=null) {
    def msg = msg_prefix
    if(start_ts != null) {
        def end_ts = System.currentTimeMillis()
        def taken_ms = end_ts - start_ts
        if(taken_ms < 0) {
            // Just incase there was some weird time skew...
            taken_ms = 0
        }
        def taken_sec = String.format("%.3f", taken_ms / 1000.0)
        def taken_m = String.format("%.3f", (taken_ms / 1000.0) / 60.0)
        msg += " has finished in " + taken_m + "m " + taken_sec + "s"
    }
    else {
        msg += " has finished"
    }
    msg += msg_postfix
    slackSend(color: 'good', message: msg, failOnError: false)
}

/**
 * Start a job, notifying its being started in slack.
 *
 * It must be ran with-in a node context (due to bugs?).
 */
def start(String msg_extra, Boolean show_build=true) {
    // For some odd reason we have to do this to get the BUILD_USER
    // information, which is sorta a PITA...
    def msg = msg_prefix
    if(show_build) {
        wrap([$class: 'BuildUser']) {
            msg += " *initiated* by"
            msg += " <mailto:${env.BUILD_USER_EMAIL}|${env.BUILD_USER}>"
            build = currentBuild.getRawBuild()
            if(build != null) {
                eta = (build.getEstimatedDuration() / 1000.0)
                msg += " [ETA=" + String.format("%.3f", eta / 60.0) + "m] "
                build = null
            }
        }
    }
    msg += msg_postfix
    if(msg_extra) {
        msg += "\n"
        msg += msg_extra
    }
    slackSend(color: 'normal', message: msg, failOnError: false)
    return System.currentTimeMillis()
}

/**
 * Perform something and notify it failing on slack.
 */
def fail_to_slack(Closure body) {
    try {
        return body.call()
    }
    catch(e) {
        def msg = msg_prefix
        msg += " had an unexpected failure "
        def emoji_choices = emojis['sad']
        def rnd = new Random()
        msg += emoji_choices[rnd.nextInt(emoji_choices.size())]
        msg += " " + msg_postfix
        msg += "\n*Cause:* "
        msg += "`" + e.toString().replace("\n", "\\n") + "`"
        slackSend(color: 'warning', message: msg, failOnError: false)
        // Reraise so that the stage dies as well...
        throw e
    }
}

return this;
