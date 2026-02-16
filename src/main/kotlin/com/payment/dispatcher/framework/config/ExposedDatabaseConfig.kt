package com.payment.dispatcher.framework.config

import io.agroal.api.AgroalDataSource
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jetbrains.exposed.sql.Database
import org.jboss.logging.Logger

/**
 * Initializes Kotlin Exposed with the Quarkus-managed Agroal DataSource.
 * Exposed uses this connection for all `transaction { }` blocks.
 */
@ApplicationScoped
class ExposedDatabaseConfig {

    @Inject
    lateinit var dataSource: AgroalDataSource

    companion object {
        private val log = Logger.getLogger(ExposedDatabaseConfig::class.java)
    }

    @PostConstruct
    fun init() {
        Database.connect(dataSource)
        log.info("Kotlin Exposed initialized with Quarkus Agroal DataSource")
    }
}
