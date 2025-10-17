package com.luna712

import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.R

import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.syncproviders.SyncRepo
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.AuthLoginResponse
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.AuthLoginRequirement

 class NginxApi : SyncAPI() {
     override val name = "Nginx"
     override val idPrefix = "nginx"
     override val icon = R.drawable.ic_baseline_extension_24
     override val createAccountUrl = "https://www.cloudstream.cf/docs/users/use-nginx.md"
     override val inAppLoginRequirement = AuthLoginRequirement(
         password = true,
         username = true,
         server = true,
    )

     companion object {
         const val NGINX_USER_KEY: String = "nginx_user"
     }

     private fun getLatestLoginData(): AuthLoginResponse? {
         return getKey(SyncRepo(this).accountId.toString(), NGINX_USER_KEY)
     }

     override suspend fun user(token: AuthToken?): AuthUser? {
         val data = getLatestLoginData() ?: return null
         return AuthUser(name = data.username ?: data.server, id = SyncRepo(this).accountId)
     }

     override suspend fun login(form: AuthLoginResponse): AuthToken? {
         if (form.server.isNullOrBlank()) return null // we require a server
         // switchToNewAccount()
         setKey(SyncRepo(this).accountId.toString(), NGINX_USER_KEY, form)
         // registerAccount()
         initializeData()

         return AuthToken(payload = form.toJson())
     }

     private fun initializeData() {
         val data = getLatestLoginData() ?: run {
             NginxProvider.overrideUrl = null
             NginxProvider.loginCredentials = null
             return
         }
         NginxProvider.overrideUrl = data.server?.removeSuffix("/") + "/"
         NginxProvider.loginCredentials = "${data.username ?: ""}:${data.password ?: ""}"
     }
 }
