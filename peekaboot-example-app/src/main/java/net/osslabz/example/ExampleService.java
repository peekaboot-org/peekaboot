package net.osslabz.example;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;


@Service
public class ExampleService {


    @Cacheable(cacheNames = "cache1")
    public Long getLong(int i) {

        return (long) i;
    }
}