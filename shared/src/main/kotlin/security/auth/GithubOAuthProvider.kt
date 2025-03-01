/*
 * Copyright (C) 2017/2021 e-voyageurs technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.tock.shared.security.auth

import ai.tock.shared.Executor
import ai.tock.shared.injector
import ai.tock.shared.property
import ai.tock.shared.provide
import ai.tock.shared.security.TockUser
import ai.tock.shared.security.TockUserListener
import ai.tock.shared.security.TockUserRole
import ai.tock.shared.vertx.WebVerticle
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.ext.auth.oauth2.OAuth2Auth
import io.vertx.ext.auth.oauth2.providers.GithubAuth
import io.vertx.ext.web.handler.AuthHandler
import io.vertx.ext.web.handler.OAuth2AuthHandler
import io.vertx.ext.web.handler.SessionHandler
import mu.KLogger
import mu.KotlinLogging

/**
 *
 */
internal class GithubOAuthProvider(
    vertx: Vertx,
    private val oauth2: OAuth2Auth = GithubAuth.create(
        vertx,
        property("tock_github_oauth_client_id", "CLIENT_ID"),
        property("tock_github_oauth_secret_key", "SECRET_KEY")
    ).rbacHandler { _, _, handler ->
        // TODO better
        handler.handle(Future.succeededFuture(true))
    }
) : SSOTockAuthProvider(vertx), OAuth2Auth by oauth2 {

    val logger: KLogger = KotlinLogging.logger {}
    private val executor: Executor get() = injector.provide()

    override fun createAuthHandler(verticle: WebVerticle): AuthHandler =
        OAuth2AuthHandler.create(this)

    override fun protectPaths(
        verticle: WebVerticle,
        pathsToProtect: Set<String>,
        sessionHandler: SessionHandler
    ): AuthHandler {
        val authHandler =
            super.protectPaths(verticle, pathsToProtect, sessionHandler)

        (authHandler as OAuth2AuthHandler).apply {
            setupCallback(verticle.router.get(callbackPath(verticle)))
        }

        verticle.router.route("/*")
            .handler {
                val user = it.user()
                if (user != null && user !is TockUser) {
                    executor.executeBlocking {
                        val login = RetrofitGithubClient.login(user.principal().getString("access_token"))
                        val tockUser = injector.provide<TockUserListener>().registerUser(
                            TockUser(login, login, TockUserRole.values().map { r -> r.name }.toSet())
                        )
                        it.setUser(tockUser)
                        it.next()
                    }
                } else {
                    it.next()
                }
            }

        return authHandler
    }

    override fun excludedPaths(verticle: WebVerticle): Set<Regex> =
        super.excludedPaths(verticle) + callbackPath(verticle).toRegex()

    private fun callbackPath(verticle: WebVerticle): String = "${verticle.basePath}/callback"
}
