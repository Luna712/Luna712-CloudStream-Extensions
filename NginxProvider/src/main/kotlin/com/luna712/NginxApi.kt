 package com.luna712

 import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
 import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
 import com.lagradost.cloudstream3.R
 import com.lagradost.cloudstream3.syncproviders.AuthAPI

import com.lagradost.cloudstream3.syncproviders.SyncAPI
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
         return getKey(accountId, NGINX_USER_KEY)
     }

     override fun loginInfo(): AuthAPI.LoginInfo? {
         val data = getLatestLoginData() ?: return null
         return AuthAPI.LoginInfo(name = data.username ?: data.server, accountIndex = accountIndex)
     }

     override suspend fun login(form: AuthLoginResponse): AuthToken? {
         if (form.server.isNullOrBlank()) return null // we require a server
         switchToNewAccount()
         setKey(accountId, NGINX_USER_KEY, form)
         registerAccount()
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
