package org.peekaboot.example.repository;

import org.peekaboot.example.entity.Person;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonRepository extends JpaRepository<Person, Long> {
}
