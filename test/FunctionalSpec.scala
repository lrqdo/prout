import lib._
import lib.travis.TravisApiClient
import org.eclipse.jgit.lib.ObjectId.zeroId

class FunctionalSpec extends Helpers with TestRepoCreation with Inside {

  "Update repo" must {

    "not spam not spam not spam" in {
      implicit val repoPR = mergePullRequestIn(createTestRepo("/feature-branches.top-level-config.git.zip"), "feature-1")

      repoPR setCheckpointTo zeroId

      eventually {
        whenReady(repoPR.githubRepo.pullRequests.list(ClosedPRsMostlyRecentlyUpdated).all()) {
          _ must have size 1
        }
      }

      scan(shouldAddComment = false) {
        labelsOn(_) must contain only "Pending-on-PROD"
      }

      repoPR setCheckpointTo "master"

      scan(shouldAddComment = true) {
        labelsOn(_) must contain only "Seen-on-PROD"
      }

      scanShouldNotChangeAnything()
    }

    "not act on a pull request if it does not touch a .prout.json configured folder" in {
      implicit val repoPR = mergePullRequestIn(createTestRepo("/multi-project.master-updated-before-feature-merged.git.zip"), "bard-feature")

      repoPR setCheckpointTo zeroId

      scanShouldNotChangeAnything()

      repoPR setCheckpointTo "master"

      scanShouldNotChangeAnything()
    }

    "act on a pull request if it touches a .prout.json configured folder" in {
      implicit val repoPR = mergePullRequestIn(createTestRepo("/multi-project.master-updated-before-feature-merged.git.zip"), "food-feature")

      repoPR setCheckpointTo zeroId

      scan(shouldAddComment = false) {
        labelsOn(_) must contain only "Pending-on-PROD"
      }

      repoPR setCheckpointTo "master"

      scan(shouldAddComment = true) {
        labelsOn(_) must contain only "Seen-on-PROD"
      }

      scanShouldNotChangeAnything()
    }

    "report an overdue merge without being called" in {
      implicit val repoPR = mergePullRequestIn(createTestRepo("/impatient-top-level-config.git.zip"), "feature-1")

      repoPR setCheckpointTo zeroId

      scan(shouldAddComment = false) {
        labelsOn(_) must contain only "Pending-on-PROD"
      }

      waitUntil(shouldAddComment = true) {
        labelsOn(_) must contain only "Overdue-on-PROD"
      }

      scanShouldNotChangeAnything()

      repoPR setCheckpointTo "master"

      scan(shouldAddComment = true) {
        labelsOn(_) must contain only "Seen-on-PROD"
      }
    }

    "report a broken site as overdue" in {
      implicit val repoPR = mergePullRequestIn(createTestRepo("/impatient-top-level-config.git.zip"), "feature-1")

      repoPR setCheckpointFailureTo new Exception("This website went Boom!")

      scan(shouldAddComment = false) {
        labelsOn(_) must contain only "Pending-on-PROD"
      }

      waitUntil(shouldAddComment = true) {
        labelsOn(_) must contain only "Overdue-on-PROD"
      }

      scanShouldNotChangeAnything()

      repoPR setCheckpointTo "master"

      scan(shouldAddComment = true) {
        labelsOn(_) must contain only "Seen-on-PROD"
      }
    }

    "trigger a post-deploy travis build" in {
      val testUserLogin = github.getUser().futureValue.result.login
      val githubRepo: Repo = github.getRepo(RepoId(testUserLogin, "test-travis-builds")).futureValue

      val branchName = System.currentTimeMillis().toString

      val masterSha = shaForDefaultBranchOf(githubRepo)

      val createdRef = githubRepo.refs.create(CreateRef(s"refs/heads/$branchName", masterSha)).futureValue
      createdRef.objectId mustEqual masterSha

      val newScratchFile = s"junk-folder/$branchName/${Random.alphanumeric.take(8).mkString}"
      val fileContents = Base64.getEncoder.encodeToString("foo".getBytes)
      githubRepo.contents2.put(newScratchFile, CreateFile("message", fileContents, Some(branchName))).futureValue

      implicit val repoPR = mergePullRequestIn(githubRepo, branchName)

      repoPR setCheckpointTo "master"

      scan(shouldAddComment = true) {
        labelsOn(_) must contain only "Seen-on-PROD"
      }

      val t = new TravisApiClient(githubToken)
      eventually {
        whenReady(githubRepo.combinedStatusFor(githubRepo.default_branch)) {
          combinedStatus =>
            val status = combinedStatus.statuses.find(_.context.startsWith("continuous-integration/travis-ci")).get

            val buildId = Uri.parse(status.target_url).pathParts.last.part
            whenReady(t.build(buildId)) {
              buildResponse => inside(buildResponse) {
                case JsSuccess(b, _) =>
                  inside (b.build.config \ "script") { case JsDefined(script) =>
                    script mustEqual JsString("echo Prout - afterSeen script")
                  }
              }
            }
        }


      }
    }

    "be able to hit Ophan" in {
      val checkpoint = Checkpoint("PROD", CheckpointDetails("https://dashboard.ophan.co.uk/login", Period.parse("PT1H")))
      whenReady(CheckpointSnapshot(checkpoint)) { s =>
        s must not be 'empty
      }
    }
  }
}