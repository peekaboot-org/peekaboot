package org.peekaboot.testingapp.repository;

import org.peekaboot.testingapp.entity.Person;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRepository extends JpaRepository<Person, Long> {
}
