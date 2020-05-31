package io.taggit.services

import io.taggit.common.*
import io.taggit.db.Dao
import io.taggit.db.Dao.UsersTable
import io.taggit.db.Dao.completeRepoSyncJob
import io.taggit.db.Dao.createNewRepoSyncJob
import io.taggit.db.Dao.deleteGitStarUser
import io.taggit.db.Dao.deleteTagFromRepo
import io.taggit.db.Dao.getAllDistinctTags
import io.taggit.db.Dao.getCurrentUserByGithubUserId
import io.taggit.db.Dao.getGitStarUser
import io.taggit.db.Dao.getMostRecentUnfinishedRepoSyncJob
import io.taggit.db.Dao.getRepoSyncJobUsingId
import io.taggit.db.Dao.getUserRepos
import io.taggit.db.Dao.getUserReposByTags
import io.taggit.db.Dao.getUserToken
import io.taggit.db.Dao.insertGitstarsUser
import io.taggit.db.Dao.insertRepo
import io.taggit.db.Dao.insertTagInRepo
import io.taggit.db.Dao.updateGitStarUser
import io.taggit.db.Dao.updateGitstarsUser
import io.taggit.db.Dao.updateRepoSyncJobError
import io.taggit.db.Dao.updateRepoSyncJobProgressAndStatus
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import me.liuwj.ktorm.dsl.eq
import me.liuwj.ktorm.dsl.select
import me.liuwj.ktorm.dsl.where
import mu.KotlinLogging
import java.util.*

object TaggitService {
    private val logger = KotlinLogging.logger { }

    fun loginOrRegister(token: String): UUID {
        val githubUser = getUserData(token)
        val existingUser = getCurrentUserByGithubUserId(githubUser.id)
        // if existing user is present update, otherwise insert a new user
        if (existingUser.isNotEmpty()) {
            updateGitstarsUser(githubUser, existingUser[0].accessToken, token)
        } else {
            insertGitstarsUser(githubUser, token)
        }
        return UsersTable.select(UsersTable.id).where {
            UsersTable.githubUserId eq githubUser.id
        }
            .map { queryRowSet -> queryRowSet[UsersTable.id] }[0]!!
    }

    fun getUser(userId: UUID): GitstarUser {
        return getGitStarUser(userId)[0]
    }

    fun updateUser(userId: UUID, update: GitStarUserUpdate): GitstarUser {
        return updateGitStarUser(userId, update)[0]
    }

    fun deleteUser(userId: UUID) {
        deleteGitStarUser(userId)
    }

    fun getUserReposPaged(userId: UUID, pageNm: Int?, pageSize: Int?): PagedResponse<GitStarsRepo> {
        val limit = (pageSize ?: Constants.DEFAULT_PAGE_SIZE).let {
            if (it <= 0) {
                throw IllegalArgumentException("pageSize field need to be greater than or equal to 1")
            } else {
                it
            }
        }
        val offset = (pageNm ?: Constants.DEFAULT_PAGE_NM).let {
            if (it <= 0) {
                throw IllegalArgumentException("pageNm fields need to be greater than or equal to 1")
            } else {
                // reduce by 1 since the database offset is zero indexed
                (it - 1).times(limit)
            }
        }
        return try {
            Dao.getUserReposPaged(userId, offset, limit)
        } catch (ex: Exception) {
            println(ex.localizedMessage)
            PagedResponse(emptyList(), Constants.DEFAULT_PAGE_NM, Constants.DEFAULT_PAGE_SIZE, 0)
        }
    }

    fun syncUserRepos(userId: UUID): UUID {
        createNewRepoSyncJob(userId)
        val syncJob = getMostRecentUnfinishedRepoSyncJob(userId)
        GlobalScope.launch {
            val token = getUserToken(userId)
            if (token.isNullOrEmpty()) {
                updateRepoSyncJobError(
                    "No user access token found, this is possibly due to user being deleted",
                    syncJob.id
                )
            } else {
                updateUserRepos(userId, token, syncJob.id)
                updateRepoSyncJobProgressAndStatus("Update completed!", 1.0F, syncJob.id)
                completeRepoSyncJob(syncJob.id)
            }
        }
        return syncJob.id
    }

    fun getsyncJob(jobId: UUID): RepoSyncJob {
        return getRepoSyncJobUsingId(jobId)
    }

    fun updateUserRepos(userId: UUID, token: String, syncJobId: UUID) {
        try {
            logger.info { "Getting current syncd user repos for userId: $userId" }
            updateRepoSyncJobProgressAndStatus("Checking for pre syncd repos", 0.3F, syncJobId)
            val currentUserRepoIds = getUserRepos(userId)
                .map { it.repoId }
            updateRepoSyncJobProgressAndStatus("Pulling current stargazing data from Github", 0.6F, syncJobId)
            logger.info { "Pulling user stargazing data from github" }
            val currentStargazingData = getUserStargazingData(token)
            updateRepoSyncJobProgressAndStatus("Updating syncd repos with new stargazing data", 0.9F, syncJobId)
            logger.info { "Checking for repos that haven't been syncd before" }
            currentStargazingData.forEach {
                if (currentUserRepoIds.notContains(it.id)) {
                    // only add the repo for the user if not previously added
                    logger.debug { "Previously unsyncd repo ${it.name} found, syncing..." }
                    insertRepo(it, userId)
                    logger.debug { "done!" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Unable to sync user stargazing data" }

        }
    }

    fun addTag(repoId: UUID, tagInput: TagInput): GitStarsRepo {
        return insertTagInRepo(repoId, tagInput.tag)
    }

    fun deleteTag(repoId: UUID, tagToDelete: String): GitStarsRepo {
        return deleteTagFromRepo(repoId, tagToDelete)
    }

    fun getAllTags(userId: UUID): List<String> {
        return getAllDistinctTags(userId)
    }

    fun searchUserRepoByTags(userId: UUID, tags: List<String>) = getUserReposByTags(userId, tags)
}
