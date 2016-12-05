#!/bin/bash

# This is a script that should be plugged into the following:
#
# https://wiki.jenkins-ci.org/display/JENKINS/Openstack+Cloud+Plugin
#
# This plugin will generate slaves using this script and bring them up
# and tear them down (after some configured time); it should be setup so
# that it works for your env.

set -x

echo "Starting slave [$(hostname -f)] setup"'!'

# Install various packages that we want on all slaves (regardless
# of version or distribution).

yum install -y python-devel
yum install -y rpm-build gcc \
               make autoconf mock createrepo \
               wget jq

# Need this one for eventual testing of databases (for now leave it out).
# yum install -y mysql-server

# For various java things (and groovy checking/validation).
yum install -y java java-devel

# Setup and start docker (since we'll be using it extensively).
#
# For now pick this version (we can move the version in the future).
docker_version="1.12.0"
cat << EOF > /etc/yum.repos.d/docker.repo
[dockerrepo]
name=Docker Repository
baseurl=https://yum.dockerproject.org/repo/main/centos/7/
enabled=1
gpgcheck=1
gpgkey=https://yum.dockerproject.org/gpg
EOF
yum clean all && yum update -y
yum -y install "docker-engine-$docker_version"
mkdir -p /etc/docker/
cat << EOF > /etc/docker/daemon.json
{
    "bip": "192.168.180.1/22",
    "fixed-cidr": "192.168.180.0/22"
}
EOF
systemctl enable docker.service
systemctl start docker
service docker status
groupadd docker

tobe_user="jenkins"
sudoers_file="98-${tobe_user}"

id -u $tobe_user &>/dev/null
if [ $? -ne 0 ]; then
    useradd $tobe_user --groups root --gid 0 -m -s /bin/bash -d "/home/$tobe_user"
fi

# Ensure the jenkins user can interact with docker (otherwise
# we will get permission denied issues when interacting with it).
usermod -aG "docker" "$tobe_user"

if [ ! -f "/etc/sudoers.d/$sudoers_file" ]; then
    echo -e "# Automatically generated at slave creation time." > /etc/sudoers.d/$sudoers_file
    echo -e "# Do not edit.\n" >> /etc/sudoers.d/$sudoers_file
    echo -e "$tobe_user ALL=(ALL) NOPASSWD:ALL\n" >> /etc/sudoers.d/$sudoers_file
fi

# If any jobs later at some point do any git (such as tag) work, make sure
# that they have a valid email name and email setup...
git config --system user.name "Jenkins"

# Change as needed... (probably only good for godaddy)
git config --system user.email "elsprod@godaddy.com"

# Disable ssh key checking (for now) into (our) github...
cat << EOF >> /etc/ssh/ssh_config

# Added automatically, do not edit.
Host github.secureserver.net
   StrictHostKeyChecking no
   UserKnownHostsFile /dev/null

EOF

# Use a tempdir and then move the .ssh directory into
# place so that sshd doesn't start allowing connections in,
# until this is fully setup.
tmp_ssh_dir="/home/$tobe_user/.ssh.tmp/"
final_ssh_dir="/home/$tobe_user/.ssh/"

mkdir -pv $tmp_ssh_dir

chmod 0700 $tmp_ssh_dir
chown $tobe_user $tmp_ssh_dir

# Get the right private key from your jenkins master
# and put it here (this is only needed so that these slaves
# can checkout from internal git).
cat << EOF > $tmp_ssh_dir/id_rsa

EOF

chown $tobe_user $tmp_ssh_dir/id_rsa
chmod 600 $tmp_ssh_dir/id_rsa

# Ensure it has the right public key also
ssh-keygen -y -f "$tmp_ssh_dir/id_rsa" > "$tmp_ssh_dir/id_rsa.pub"
chown $tobe_user $tmp_ssh_dir/id_rsa.pub

# Get the right public key from your jenkins master
# and put it here.
touch $tmp_ssh_dir/authorized_keys
cat << EOF > $tmp_ssh_dir/authorized_keys

EOF

chmod 600 $tmp_ssh_dir/authorized_keys
chown $tobe_user $tmp_ssh_dir/authorized_keys

# For when things don't work out (useful to be able to look at why)...
mkdir -p /etc/systemd/system/docker.service.d
cat << EOF > /etc/systemd/system/docker.service.d/docker.conf
[Service]
ExecStart=
ExecStart=/usr/bin/dockerd -D
EOF

# Ensure all changes are now reflected...
systemctl daemon-reload
systemctl restart docker

# Helper script to clean off docker images...
cat << EOF > /usr/bin/wipe_docker_images
#!/bin/bash
set -x
# remove orphan containers
containers=\$(docker ps -a -q)
if [ -n "\$containers" ]; then
    docker stop \$containers
    docker rm \$containers
fi
# remove orphan images
images=\$(docker images -q --filter "dangling=true")
if [ -n "\$images" ]; then
    docker rmi -f \$images
fi
# removal any other images
images=\$(docker images -q)
if [ -n "\$images" ]; then
    docker rmi -f \$images
fi
EOF
chmod +x /usr/bin/wipe_docker_images

# Wipes leftover docker images daily.
tmp_cron=$(mktemp)
cat << EOF > $tmp_cron
0 1 * * * /usr/bin/wipe_docker_images
EOF
crontab $tmp_cron
rm $tmp_cron

# To view logs then run `journalctl -u docker.service | less`

# Ok, fully setup, now move/rename it (atomically ideally).
#
# Once this is moved ssh access will begin, so do it as late as u can.
mv $tmp_ssh_dir $final_ssh_dir

echo "Slave [$(hostname -f)] setup all done"'!'
echo "Jenkins should be connecting into this node very soon..."
