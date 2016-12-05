// Bunch of constants for nova
project = "nova"
venv_py_version = "python2.7"
unit_test_path = "./${project}/tests/unit"
unit_test_timeout = 160
maintainers = "Cloud Platform [ELS] (cloud@godaddy.com)"
artifactory_base = "artifactory.secureserver.net"
if(docker_repo) {
    kolla_image_namespace = "${docker_repo}.${artifactory_base}"
}
else {
    kolla_image_namespace = "kolla"
}
