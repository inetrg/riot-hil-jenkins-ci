# RIOT HiL Jenkins CI

This is a dockerized version of the HiL Jenkins CI.
It uses Configuration-as-Coded and plugins to get a reproducible CI setup.
A [testing Github account](https://github.com/riot-hil-bot) is created to simplify the local testing deployment as well as the staging phase.
[Credentials](https://trac.inet.haw-hamburg.de/trac/wiki/riot/ci/hil) are available to members of the iNET working group.

### Design Decisions

- the casc-config is baked into a non-persistent storage to force all changes in the configuration to be documented
- The local testing does not include persistent storage of jenkins_home as we want to test with a fresh start

## Docker Args

In order to have defaults set to deploy but still possible to test on a local machine, many arguments can be input to work with ones own repo and setup.

Please note that much of this is handled if using the [bot account](https://github.com/riot-hil-bot).

- `GITHUB_OAUTH_CLIENT_ID`: This is the client id you get when setting up a github oauth, for testing [localhost](https://docs.github.com/en/free-pro-team@latest/developers/apps/authorizing-oauth-apps#localhost-redirect-urls) urls can be used.

- `ADMIN_0`: Github username for admin account.
Up to `ADMIN_2` are available and populated with defaults.
Set all to yourself if security is an issue.

- `GITHUB_UID`: A constant but unique numbers (that seemingly has no effect).

- `GITHUB_REPO_OWNER`: The owner of the github repo containing RobotFW-Tests.
This can be set to your repo for testing purposes.

- `GITHUB_CI_USERNAME`: The username that is connected to the `client_secrets` of github oauth and `repo_token` for token access to github.

- `JENKINS_PREFIX`: The prefix to the root of the webserver.

## Secrets
The default location of the secrets directory is `/opt/riot-hil-jenkins-ci-secrets/`.
This can be overridden if needed when running the docker container but is needed for `docker-compose`.

### `SECRETS` Directory
A `SECRETS` volume should be mounted with the following files and directory structure (pay attention to the permissions and users):
```
<path_to_my_secrets>
+-- jenkins_ssh_keys
|   +-- id_rsa_master
|   +-- id_rsa_master.pub
+-- github_tokens
|   +-- repo_token
+-- github_oauth
|   +-- client_secrets
```

- `id_rsa_master`: ssh private key that for accessing all the jenkins nodes.
- `id_rsa_master.pub`: (optional) ssh public to be put in the jenkins nodes authorized_keys folder.
- `repo_token`: Github token with `repo` attribute, should match the `GITHUB_CI_USERNAME` value.
This is used for scanning and api calls to the RobotFW-Tests and RIOT repos
- `client_secrets`: The secrets for the oauth access to Jenkins.
This should be paired with the `GITHUB_CI_USERNAME` parameter.
See [Github OAuth](https://docs.github.com/en/free-pro-team@latest/developers/apps/authorizing-oauth-apps#localhost-redirect-urls) for more info

## Local testing
To test changes to the CI locally the [docker_compose_local.yml](docker_compose_local.yml) can be used with:

```
docker-compose -f docker-compose-local.yml up
```

Then check `localhost:8080` in your browser.

This requires the secrets to be available and in a known directory.
It uses the [riot-hil-bot](https://github.com/riot-hil-bot) account for OAuth and a fork of the [RobotFW-tests](https://github.com/riot-hil-bot/RobotFW-tests) as the experimental account.
This means pushing and triggering should be done with that account.

Assuming you are not on the HAW address there should only be the `test_node` available as a slave node.
This should be used with caution as other instances may be connecting and using it.

## Staging
Staging should be use to test things before deploying and should be run on the HAW CI server.
This means that it should have access to all the nodes.
The only difference is that uses a different url and uses the [RobotFW-tests](https://github.com/riot-hil-bot/RobotFW-tests) from [riot-hil-bot](https://github.com/riot-hil-bot).

Note that this keeps a named volume in `users/docker/volumes/staging_jenkins_home`

## Deploying

TODO


## Useful notes

- We need to change the ownership of the secret files when copying
- update the nginx conf (/etc/nginx/conf.d/riot-ci.conf to expose testing ports
- The job must be reloaded after starting for credentials to run.
- The pis require the authorized keys to be entered in the jenkins user `.ssh`.
- The old deployment command was:
```
docker run -d -v hil_jenkins_home:/var/jenkins_home -p 8080:8080 -p 50000:50000 --env JENKINS_OPTS="--prefix=/hil" --restart always --name riot-hil-jenkins jenkins/jenkins:lts
```
- Jenkins home location: `users/docker/volumes/hil_jenkins_home/`

## Known Issues
- It seems that reading the `github_tokens/repo_token` has a problem and is not reading correctly, entering the token manually seems to work
- The `triggers{period_timer(1)} is deprecated but the cron replacement doesn't seems to work
- Job description doesn't seem to do anything...

# TODO

- Setup the global test node
- verify the ssh keys
- determine what will be done for the proper deployment
- update the names to use the new subdomain