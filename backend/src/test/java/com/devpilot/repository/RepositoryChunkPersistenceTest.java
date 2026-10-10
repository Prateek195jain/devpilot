package com.devpilot.repository;

import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(showSql=false)
@ActiveProfiles("test")
class RepositoryChunkPersistenceTest extends RepositoryChunkPersistenceContract {
}
