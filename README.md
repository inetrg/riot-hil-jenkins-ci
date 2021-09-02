# RIOT HiL Jenkins CI

This is a dockerized version of the HiL Jenkins CI.
It uses Configuration-as-Coded and plugins to get a reproducible CI setup.
Production jobs are controlled with the job-dsl and job_scripts from this repo.
A [testing Github account](https://github.com/riot-hil-bot) is created to simplify the testing deployment and control the github oauth.
[Credentials](https://trac.inet.haw-hamburg.de/trac/wiki/riot/ci/hil) are available to members of the iNET working group.

### Design Decisions

- the casc-config is baked into a non-persistent storage to force all changes in the configuration to be documented
- local setup can be simulated on ones own computer using the setup script to deploy
- the job scripts are less transparent from the configuration but more stable this way, and one can always just start a pipeline with a manual script entered

## Docker Args

There are some configurable env vars and args.

- `GITHUB_UID`: A constant but unique numbers (that seemingly has no effect).

- `GITHUB_CI_USERNAME`: The username that is connected to the `github_token` that is normally connected to the `riot-hil-bot` account.

- `JENKINS_PREFIX`: The prefix to the root of the webserver.

- `CASC_ENV`: Selects the additional configs to add, for example, `prod` brings in all the production nodes.
Note that this must remain an arg as it is needed for build time copy of the config.
The local directory is ignored by the `.gitignore` and gets populated by running setup scripts in the `scripts` folder.

## Secrets

The secrets mount must contain specific filenames for everything.
On the production/staging the path is known and accessible to admins.
Local uses the `$USER` .ssh folder, this means that the `github_token` file will need to be added or the location of the default secrets folder for the local deployment will have to be changed.

### `SECRETS` Directory
A `SECRETS` volume should be mounted with the following files and directory structure (pay attention to the permissions and users):
```
<path_to_my_secrets>
+-- id_rsa
+-- id_rsa.pub        (only needed for local deployment)
+-- github_token
+-- oauth_client_id   (not needed for local deployment)
+-- oauth_secrets     (not needed for local deployment)
```

- `id_rsa`: ssh private key that for accessing all the jenkins nodes.
- `id_rsa.pub`: ssh public to be put in the jenkins nodes authorized_keys folder (used when setting up local jenkins agents to give master node access).
- `github_token`: Github token with `repo` attribute, should match the `GITHUB_CI_USERNAME` value.
This is used for all github calls, and gets around issues of private repos and rate limiting.
**REMOVE ANY NEWLINES FROM THIS FILE: `echo -n "my_token" > github_token`.**
- `oauth_client_id`: Not really a secret, the ID used for github oauth (not needed for local deployment).
- `oauth_secrets`: The secrets for the oauth access to Jenkins (not needed for local deployment).
This should be paired with the `GITHUB_CI_USERNAME` parameter.
See [Github OAuth](https://docs.github.com/en/free-pro-team@latest/developers/apps/authorizing-oauth-apps#localhost-redirect-urls) for more info

## Manual Steps

There are a few manual steps needed as automation of these are a challenge.

1. The token (secret text and username/passwords) credentials must be manually
copied as the readfile functionality seems to struggle and using envs are not
deemed safe.

## Local testing

A [deploy_local.sh](scripts/deploy_local.sh) script is made to help test deployments locally.
Please review the script and what it does as that is probably better documentations.
This will require some boards plugged into the machine if full testing is required (PHiLIP and DUT per test node).
*There is an assumption that the local machine can run RIOT and RobotFW-tests.*

A quick description of what happens:
1. Plug in n PHiLIP boards and n DUT boards in, noting the ports (also make sure the DUT boards vary as RIOT may get confused which board to flash).
1. Run the `deploy_local.sh -n <amount_of_philip_dut_pairs>` script
1. A jenkins user will be created and configured to support the nodes and build, note that this will be done on the machine and require sudo.
1. The ssh key will added to the jenkins user authorized keys.
1. There will be prompts for board configuration parameters of PHiLIP and the DUT
1. The configuration for the test node and build node will be added into the `casc_configs/local` folder
1. Any old instanced of the local deployment will be shut down and the volumes removed.
1. A new instance of the local deployment will be build and started.
1. Finally check `localhost:8080` in your browser.

DO NOT EXPOSE THIS PORT AS THERE ARE NO ADMIN PROTECTIONS (anyone can decrypt and steal the keys and tokens).

## Staging
Staging should be use to test things before deploying production.
This means that it should have access to an external test node and the build servers.

```
docker-compose -f docker-compose.staging.yml up -d --build
```

Note that this keeps a named volume in `users/docker/volumes/staging_jenkins_home`

Please remove the volume when shutting it down as we want to test an empty deployment.
```
docker-compose -f docker-compose.staging.yml down -v
```

## Deploying Production

Backups on nightlies should be running via a cron job and stored in `/net/pub/hil_backups`.

```
docker-compose -f docker-compose.prod.yml up --build
```

Production should only use mounted volumes as they are less likely to be accidentally wiped by someone like me.

## Useful notes

- We need to change the ownership of the secret files when copying
- update the nginx conf (`/etc/nginx/conf.d/hil.riot-ci.conf `to expose testing ports
- The pis require the authorized keys to be entered in the jenkins user `.ssh`.
- The old deployment command was:
- To update the plugins, get the current list from script console with:
```groovy
master_plugins="""
PLUGINS_FROM_plugins_master.txt
"""
master_plugins = master_plugins.split()

plug_strings = []
def plugins = jenkins.model.Jenkins.instance.getPluginManager().getPlugins()
plugins.each {
  if (it.getShortName() in master_plugins) {

  	plug_strings << "${it.getShortName()}:${it.getVersion()}"
  }
}
println(plug_strings.sort().join("\n"))
```
