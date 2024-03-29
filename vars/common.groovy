/* common =================================================================== */

/* common master ============================================================ */

/* Helps make the checkout simpler by adding in all information common to
 * this setup.
 * Note:
 * - either `url` or `owner, repo` must specified.
 * - if pr has a number it will be used and branch will be ignored
 * - if pr is empty branch must be specified
 */
def helperCheckoutRepo(url='', pr='', branch='', dir='.', owner='', repo='') {

    if (url == "") {
        assert owner != ''
        assert repo != ''
        url = "https://github.com/${owner}/${repo}.git"
    }
    if (pr != "" && pr != null) {
        sh 'git config --global user.name "riot-hil-bot"'
        sh 'git config --global user.email "riot-hil-bot@haw-hamburg.de"'
        chk = checkout([
            $class: 'GitSCM',
            branches: [[name: "pr/${pr}"]],
            extensions: [[$class: 'RelativeTargetDirectory',
                          relativeTargetDir: dir],
                         [$class: 'CleanBeforeCheckout'],
                         [$class: "PreBuildMerge",
                          options: [mergeTarget: "master",
                                    mergeRemote: "origin"]]],
            userRemoteConfigs: [[url: url,
                                 refspec: "+refs/pull/${pr}/head:refs/remotes/origin/pr/${pr}",
                                 credentialsId: 'github_token']]
        ])
    }
    else {
        assert branch != ''
        chk = checkout([
            $class: 'GitSCM',
            branches: [[name: "${branch}"]],
            extensions: [[$class: 'RelativeTargetDirectory',
                          relativeTargetDir: dir],
                         [$class: 'CleanBeforeCheckout']],
            userRemoteConfigs: [[url: url,
                                 credentialsId: 'github_token']]
        ])
    }
    return chk
}

def getBoardsFromNodes(boards='all') {
    if (boards == 'all') {
        boards = []
        for (node_name in nodesByLabel('HIL')) {
            node (node_name) {
                boards.push(env.BOARD)
            }
        }
        boards = boards.unique()
    }
    else {
        boards = boards.tokenize(', ')
        /* TODO: Validate if the boards are connected */
    }
    return boards
}

/* This expects a single string and will return a list of tests, running
 * this again with a list will not work out...
 */
def getTests(tests='all') {
    if (tests == 'all') {
        tests = sh returnStdout: true,
                script: """
                    for dir in \$(find tests -maxdepth 1 -mindepth 1 -type d); do
                        [ -d \$dir/tests ] && { echo \$dir ; } || true
                    done
                """, label: "Collecting tests"
    }
    tests = tests.tokenize()
    return tests
}

/* We should initialized the Map before using it, this should be done in master
 * as it doesn't always play nice with parallel nodes.
 */
def getEmptyResultsFromBoards(boards) {
    results = [:]
    for (board in boards) {
        results[board] = [:]
    }
    return results
}

/* Should only be done in master once. It takes a list of tests and boards
 * and gives a list of Maps containing named board and test. This allows
 * multiple build servers to just pop the items so we have a resolution of
 * test/board.
 */
def getBoardTestQueue(boards, tests) {
    board_test_queue = []

    for (test in tests) {
        for (board in boards) {
            board_test_queue << ["board": (board), "test": (test)]
        }
    }
    return board_test_queue
}

def stashRobotFWTests() {
    stash name: "RobotFWTestsRepo",
          excludes: "RIOT/**, RobotFW-frontend/**"
}

/* After all the tests have generated the results and stored them in the
 * this should go through the archive and generate any post-processing scripts
 * such as adding each single xunit result into one and, if required,
 * generating html pages for the results webserver.
 */
def compileResults(generate_html=False)
{
    if (generate_html) {
        ret = sh script: '''
            HIL_JOB_NAME=$(echo ${JOB_NAME}| cut -d'/' -f 1)
            ARCHIVE_DIR=${JENKINS_HOME}/jobs/${HIL_JOB_NAME}/builds/${BUILD_NUMBER}/archive/build/robot/
            if [ -d $ARCHIVE_DIR ]; then
                ./dist/tools/ci/results_to_xml.sh $ARCHIVE_DIR
                cd RobotFW-frontend
                ./scripts/xsltprocw.sh -c ../config-live.xml -b ${HIL_JOB_NAME} -n ${BUILD_NUMBER} -v /var/jenkins_home/jobs/

            fi
        ''', label: "Compile archived results"
    }
    else {
        ret = sh script: '''
            HIL_JOB_NAME=$(echo ${JOB_NAME}| cut -d'/' -f 1)
            ARCHIVE_DIR=${JENKINS_HOME}/jobs/${HIL_JOB_NAME}/builds/${BUILD_NUMBER}/archive/build/robot/
            if [ -d $ARCHIVE_DIR ]; then
                ./dist/tools/ci/results_to_xml.sh $ARCHIVE_DIR
            fi
        ''', label: "Compile archived results"
    }
}

/* This keeps track of the state that the tests were run in. This should make
 * any issues regarding reproducibility slightly easier.
 */
def archiveMetadata() {
    sh script: """
            mkdir -p build/robot
            python3 dist/tools/ci/env_parser.py -x -g -e --output=build/robot/metadata.xml
            """
    archiveArtifacts artifacts: "build/robot/metadata.xml"
}

/* Returns dict containing results numbers
 * results ->
 * [my_board_1: [my_test_1: [build: true, support: true, flash: true, test: false],
 *               my_test_2: [build: false, support: false, flash: true, test: true]],
 *  my_board_2: [my_test_1: [build: false, support: true, flash: false, test: false],
 *               my_test_2: [build: true, support: true, flash: true, test: true]]
 * ]
 *
 * return -> [boards: 2, pass: 1, fail: 2]
 */
def countResults(results)
{
    boards = 0
    passed = 0
    failed = 0

    for (board in mapToList(results)) {
        boards++

        for (test in mapToList(board.value)) {
            if (test.value['test']) {
                passed++
            }
            else if (test.value['support']) {
                failed++
            }
        }
    }
    return ["boards": boards, "pass": passed, "fail": failed]
}

/* This archives a json badge result for https://shields.io/endpoint */
def setBadge(passed, failed, boards) {
    String color = "orange"
    if (passed > 0 && failed == 0) {
        color = "green"
    }
    String filename = 'build/robot/badge.json'
    writeFile file: filename, text: "{\"schemaVersion\":1,\"label\":\"HiL\",\"message\":\"${passed} pass / ${failed} fail / ${boards} boards\",\"color\":\"${color}\"}"
    archiveArtifacts artifacts: filename
}

/* Generates a nice markdown based summary of the test */
def generateNotifyMsgMD(results)
{
/* results =
[my_board_1:[
  my_test_1:[build: true, support: true, flash: true, test: false],
  my_test_2: [build: false, support: false, flash: true, test: true]
 ],
 my_passing_board: [
  my_test_1: [build: true, support: true, flash: true, test: true],
  my_test_2: [build: true, support: true, flash: true, test: true]
 ],
 my_board_3:[
  my_test_1: [build: false, support: false, flash: false, test: false],
  my_test_2: [build: false, support: false, flash: false, test: true],
  my_test_3: [build: false, support: false, flash: true, test: false],
  my_test_4: [build: false, support: false, flash: true, test: true],
  my_test_5: [build: false, support: true, flash: false, test: false],
  my_test_6: [build: false, support: true, flash: false, test: true],
  my_test_7: [build: false, support: true, flash: true, test: false],
  my_test_8: [build: false, support: true, flash: true, test: true],
  my_test_9: [build: true, support: false, flash: false, test: false],
  my_test_10: [build: true, support: false, flash: false, test: true],
  my_test_11: [build: true, support: false, flash: true, test: false],
  my_test_12: [build: true, support: false, flash: true, test: true],
  my_test_13: [build: true, support: true, flash: false, test: false],
  my_test_14: [build: true, support: true, flash: false, test: true],
  my_test_15: [build: true, support: true, flash: true, test: false],
  my_test_16: [build: true, support: true, flash: true, test: true]
 ]
]
*/
    TEST_FAIL_EMOJI = "&#10060;"
    BUILD_FAIL_EMOJI = TEST_FAIL_EMOJI
    UNKNOWN_FAIL_EMOJI = TEST_FAIL_EMOJI
    SKIP_EMOJI = "&#128584;"
    FLASH_FAIL_EMOJI = "&#128556;"
    PASS_EMOJI = "&#9989;"

    boards = 0
    total_passed = 0
    total_failed = 0
    total_skipped = 0

    pass_msg = []
    flash_fail_msg = []
    fail_msg = []

    for (board in mapToList(results)) {
        boards++
        tests_passed = 0
        tests_failed = 0
        build_failed = 0
        flash_failed = 0
        unknown_failed = 0
        tests_skipped = 0
        failed_test_msg = []
        pass_test_msg = []
        board_emoji = PASS_EMOJI

        for (test in mapToList(board.value)) {
            link = "https://hil.riot-os.org/results/${env.JOB_NAME}/${env.BUILD_NUMBER}/${board.key}/${test.key.replace("/", "_")}/console_log.html"
            /*  test.value = [build: true, support: true, flash: true, test: false] */
            test_details = "\n|${test.key}|"
            if (test.value['test']) {
                total_passed++
                tests_passed++
                test_details += PASS_EMOJI + " pass"
                pass_test_msg += test_details
            }
            else if (!test.value['support']) {
                test_details = "\n|[${test.key}](${link})|"
                total_skipped++
                tests_skipped++
                test_details += SKIP_EMOJI + " skip"
                pass_test_msg += test_details
            }
            else {
                test_details = "\n|[${test.key}](${link})|"
                if (!test.value['build']) {
                    total_failed++
                    build_failed++
                    test_details += BUILD_FAIL_EMOJI + " build fail"
                    board_emoji = BUILD_FAIL_EMOJI
                }
                else if (!test.value['flash']){
                    total_failed++
                    flash_failed++
                    test_details += FLASH_FAIL_EMOJI + " flash fail"
                    if (board_emoji != TEST_FAIL_EMOJI &&
                        board_emoji != BUILD_FAIL_EMOJI &&
                        board_emoji != UNKNOWN_FAIL_EMOJI) {
                        board_emoji = FLASH_FAIL_EMOJI
                    }
                }
                else if (!test.value['test']){
                    total_failed++
                    tests_failed++
                    test_details += TEST_FAIL_EMOJI + " test fail"
                    board_emoji = TEST_FAIL_EMOJI
                }
                else {
                    total_failed++
                    unknown_failed++
                    test_details += UNKNOWN_FAIL_EMOJI + " unknown fail"
                    board_emoji = UNKNOWN_FAIL_EMOJI
                }
                failed_test_msg += test_details
            }
        }

        detail_msg = "<details><summary>&nbsp;&nbsp;${board_emoji}&nbsp;${board.key} "
        if (tests_failed) {
            detail_msg += "<strong>(${tests_failed} fail test)</strong> "
        }
        if (build_failed) {
            detail_msg += "<strong>(${build_failed} fail build)</strong> "
        }
        if (flash_failed) {
            detail_msg += "(${flash_failed} fail flash) "
        }
        detail_msg += "</summary>\n\n"
        detail_msg += "|PASS|FAIL|SKIP\n"
        detail_msg += "|-|-|-\n"
        detail_msg += "|${tests_passed}|${tests_failed+flash_failed+build_failed+unknown_failed}|${tests_skipped}\n\n"
        detail_msg += "|TEST|RESULT\n"
        detail_msg += "|-|-"
        detail_msg += failed_test_msg.join("")
        detail_msg += pass_test_msg.join("")
        detail_msg += "\n</details>\n"
        if (tests_failed || build_failed) {
            fail_msg += detail_msg
        }
        else if (flash_failed) {
            flash_fail_msg += detail_msg
        }
        else {
            pass_msg += detail_msg
        }
    }

    full_msg = "[HiL Test Results](${env.RUN_DISPLAY_URL})\n"
    full_msg += "|PASS|FAIL|SKIP\n"
    full_msg += "|-|-|-\n"
    full_msg += "|${total_passed}|${total_failed}|${total_skipped}\n\n"
    full_msg += "<details>\n"
    full_msg += fail_msg.join("\n")
    full_msg += flash_fail_msg.join("\n")
    full_msg += pass_msg.join("\n")
    full_msg += "</details>\n"

    return full_msg
}

/* Writes a comment on a github PR */
def notifyOnPR(owner, repo, pr, msg='default comment') {

    msg = msg.replaceAll("[\\n]", "\\\\n")
    def query = "-X POST -d '{\"body\": \"${msg}\"}' "
    query = "${query}\"https://api.github.com/repos/${owner}/${repo}/issues/"
    query = "${query}${pr}/comments\""
    withCredentials([usernamePassword(credentialsId: 'github_token',
                                      passwordVariable: 'TOKEN',
                                      usernameVariable: 'DUMMY')]) {
        sh script: '''
            curl -H "Authorization: token $TOKEN" ''' + query,
        label: "Query github api"
    }
}

/* common riot_build ======================================================== */
/* Returns a list of online builder nodes. It seems each node should be
 * cloned or copied before being used.
 */
def getActiveBuildNodes() {
    return nodesByLabel('riot_build')
}

/* Empties a queue of
 * [[board: "my_board_1", test: "my_test_1"],
 *  [board: "my_board_1", test: "my_test_2"],
 *  [board: "my_board_2", test: "my_test_1"],
 *  [board: "my_board_2", test: "my_test_2"]]
 * or something like that and builds the test for that board.
 * This is intended to be used across multiple build servers so we need to be
 * a bit special when it comes to paralellism. The `board_test_queue` is
 * assumed to be a global variable as it gets populated by the master and used
 * on the build servers. The `results` are another story. This is used to track
 * if the build was successful, skipped, or failed. Again, assumed to be
 * global as it populated in the build server and used on the test nodes.
 * It is also assumed to be initialied with all keys corresponding to boards
 * and having an empty Map that will be populated with a test key and
 * build and supported results.  Maybe it is better to look at an example:
 * [my_board_1: [my_test_1: [build: true, support: true, flash: true, test: false],
 *               my_test_2: [build: false, support: false, flash: true, test: true]],
 *  my_board_2: [my_test_1: [build: false, support: true, flash: false, test: false],
 *               my_test_2: [build: true, support: true, flash: true, test: false]]
 * ]
 * Clear as mud?
 */
def buildJobs(board_test_queue, results, extra_make_cmd = "") {
    while (board_test_queue.size() > 0) {
        try {
            def boardtest = board_test_queue.pop()
            buildJob(boardtest['board'], boardtest['test'], results, extra_make_cmd)
        }
        catch (java.util.NoSuchElementException exc) {
            println("Due to concurrency issues, it is ok try to pop")
        }
    }
}

/* Actually builds the job, look at buildJobs for more info.
 * Long story short, calls make, stashes successful binaries,
 * populates the results.
 */
def buildJob(board, test, results, extra_make_cmd = "") {
    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE',
            catchInterruptions: false) {
        results[board][test] = ['build': false, 'support': false, 'flash': false, 'test': false]
        exit_code = sh script: "RIOT_CI_BUILD=1 DOCKER_MAKE_ARGS=-j BUILD_IN_DOCKER=1 BOARD=${board} make -C ${test} clean all ${extra_make_cmd} > build_output.log 2>&1",
            returnStatus: true,
            label: "Build BOARD=${board} TEST=${test}"

        if (exit_code == 0) {
            /* Must remove all / to get stash to work */
            results[board][test]['build'] = true
            s_name = (board + "_" + test).replace("/", "_")
            try{
                stash name: s_name, includes: "${test}/bin/${board}/*.elf,${test}/bin/${board}/*.hex,${test}/bin/${board}/*.bin"
                results[board][test]['support'] = true
            }
            catch (hudson.AbortException ex) {
                archiveConsoleLog(board, test, "No binary available, probably insufficient memory")
            }
            catch (Exception ex) {
                unstable('Stashing binary failed!')
            }
        }
        else {

            def output = readFile('build_output.log').trim()
            echo output
            archiveConsoleLog(board, test, output)
            if (output.contains("There are unsatisfied feature requirements")) {
                results[board][test]['support'] = false
            }
            else {
                results[board][test]['support'] = true
                results[board][test]['build_error_msg'] = output
            }
        }
    }
}

/* common test node ========================================================= */
/* Needed to deal with groovy garbage. */
@NonCPS
def mapToList(depmap) {
    def dlist = []
    for (def entry2 in depmap) {
        dlist.add(new java.util.AbstractMap.SimpleImmutableEntry(entry2.key, entry2.value))
    }
    dlist
}

def unstashRobotFWTests() {
    unstash name: "RobotFWTestsRepo"
}

def unstashBinaries(test) {
    unstash name: "${env.BOARD}_${test.replace("/", "_")}"
}

/* Flashes binary to the DUT of the node. */
def flashTest(test)
{
    exit_code = sh script: "RIOT_CI_BUILD=1 make -C ${test} flash-only > flash.log  2>&1",
                   label: "Flash ${test}", returnStatus: true
    def output = readFile('flash.log').trim()
    echo output
    if (exit_code != 0) {
        archiveConsoleLog(env.BOARD, test, output)
        sh "exit ${exit_code}"
    }
}

/* Does all the things needed for robot tests. */
def rFTest(test, extra_test_cmd = "")
{
    def test_result = false
    def test_name = test.replaceAll('/', '_')
    sh script: "make -C ${test} robot-clean || true",
            label: "Cleaning before ${test} test"
    /* We don't want to stop running other tests since the robot-test is
     * allowed to fail */
    catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE',
            catchInterruptions: false) {
        exit_code = sh script: "make -C ${test} robot-test ${extra_test_cmd} > robot_test.log 2>&1",
                        label: "Run ${test} test", returnStatus: true
        def output = readFile('robot_test.log').trim()
        echo output
        if (exit_code != 0) {
            archiveConsoleLog(env.BOARD, test, output)
            sh "exit ${exit_code}"
        }
        test_result = true
    }
    return test_result
}

def archiveTestResults(test)
{
    def test_name = test.replaceAll('/', '_')
    def base_dir = "build/robot/${env.BOARD}/${test_name}/"
    archiveArtifacts artifacts: "${base_dir}*.xml,${base_dir}*.html,${base_dir}*.html,${base_dir}includes/*.html",
            allowEmptyArchive: true
    junit testResults: "${base_dir}xunit.xml", allowEmptyResults: true
}

/* Somewhat hacky way of adding info on build results for the tests that
 * were not run. */
def archiveFailedTestResults(test, err_msg)
{
    def test_name = test.replaceAll('/', '_')
    def dir = "build/robot/${env.BOARD}/${test_name}/xunit.xml"
    writeFile file: dir, text: """<?xml version='1.0' encoding='UTF-8'?>
<testsuite errors="0" failures="1" name="${test_name}" skipped="0" tests="1" time="0.000"><testcase classname="${test_name}.build" name="Build" time="0.000"><failure>Build failed</failure></testcase></testsuite>
"""
    archiveArtifacts artifacts: dir
    junit testResults: dir, allowEmptyResults: true
}

/* Somewhat hacky way of adding info on build results for the tests that
 * were not run. */
def archiveSkippedTestResults(test)
{
    def test_name = test.replaceAll('/', '_')
    def dir = "build/robot/${env.BOARD}/${test_name}/xunit.xml"
    writeFile file: dir, text: """<?xml version='1.0' encoding='UTF-8'?>
<testsuite errors="0" failures="0" name="${test_name}" skipped="1" tests="1" time="0.000"><testcase classname="${test_name}.build" name="Build" time="0.000"><skipped>Test not supported</skipped></testcase></testsuite>
"""
    archiveArtifacts artifacts: dir
    junit testResults: dir, allowEmptyResults: true
}

def archiveConsoleLog(board, test, log) {
    def test_name = test.replaceAll('/', '_')
    def filepath = "build/robot/${board}/${test_name}/console_log.html"

    def file_content = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${board} ${test} output</title>
  <style>
    *{box-sizing:border-box}
    body {background-color:#22272e;color:#adbac7;padding:10px;margin:0;min-height:100vh;}
    pre,code {margin:0;padding:0;}
  </style>
</head>
<body>
  <div id="container">
    <pre><code>${log}</code></pre>
  </div>
</body>
</html>
"""
    writeFile file: filepath, text: file_content
    archiveArtifacts artifacts: filepath
}

/* Tries to flash and test each test.
 *
 * If a test fails it catches and runs through the next one. Successful tests
 * uploads test artifacts.
 *
 * Required results from the buildJobs.
 */
def flashAndRFTestNodes(results, extra_test_cmd = "")
{
    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE', catchInterruptions: false) {
        stage( "${env.BOARD} setup on  ${env.NODE_NAME}"){
            /* We need to clean as the workspace may contain files from other */
            /* unstashes such as new tests that are not in master */
            cleanWs(disableDeferredWipeout: true)
            unstashRobotFWTests()
        }
        for (def test in mapToList(results[env.BOARD])) {
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE',
                        catchInterruptions: false) {
                if (test.value["support"]) {
                    if (test.value['build']) {
                        stage("${test.key}") {
                            unstashBinaries(test.key)
                            /* No need to reset as flashing and the test should manage
                            * this */
                            flashTest(test.key)
                            test.value['flash']  = true
                            test.value['test'] = rFTest(test.key, extra_test_cmd)
                            sh script: "make -C ${test.key} robot-plot",
                                label: "Generate plot for ${test} test if possible"
                            archiveTestResults(test.key)

                        }
                    }
                    else {
                        stage("Build failing ${test.key}") {
                            err_msg = test.value["build_error_msg"]
                            archiveFailedTestResults(test.key, err_msg)
                            error("Build failure ${err_msg}")
                        }
                    }
                }
                else {
                    stage("Skipping ${test.key}") {
                        archiveSkippedTestResults(test.key)
                    }
                }
            }
        }
    }
}

def riotTest(test, extra_test_cmd = "")
{
    def test_result = false
    def test_name = test.replaceAll('/', '_')
    catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE',
            catchInterruptions: false) {
        exit_code = sh script: "make -C ${test} test ${extra_test_cmd}> test.log 2>&1",
                       label: "Run ${test} test", returnStatus: true
        def output = readFile('test.log').trim()
        echo output
        if (exit_code != 0) {
            archiveConsoleLog(env.BOARD, test, output)
            sh "exit ${exit_code}"
        }
        test_result = true
    }
    return test_result
}

/* Tries to flash and test each test.
 *
 * If a test fails it catches and runs through the next one. Successful tests
 * uploads test artifacts.
 *
 * Required results from the buildJobs.
 */
def flashAndRiotTestNodes(results, extra_test_cmd = "")
{
    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE', catchInterruptions: false) {
        for (def test in mapToList(results[env.BOARD])) {
            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE', catchInterruptions: false) {
                if (test.value["support"]) {
                    if (test.value['build']) {
                        stage("${test.key}") {
                            unstashBinaries(test.key)
                            /* No need to reset as flashing and the test should manage
                            * this */
                            flashTest(test.key)
                            test.value['flash'] = true
                            test.value['test'] = riotTest(test.key, extra_test_cmd)
                        }
                    }
                    else {
                        stage("Build failing ${test.key}") {
                            error("Build failure")
                        }
                    }
                }
                else {
                    stage("Skipping ${test.key}") {
                        echo "Skipping due to test not supported"
                    }
                }
            }
        }
    }
}
