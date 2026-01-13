package net.osslabz.peekaboot.backend.fixture.repository;

import net.osslabz.peekaboot.backend.fixture.entity.Person;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRepository extends JpaRepository<Person, Long> {
}
