package com.icodesoftware.sample.service;

import com.icodesoftware.sample.model.Product;
import com.icodesoftware.sample.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private static final String CACHE_PREFIX = "product:";
    private static final Duration CACHE_TTL   = Duration.ofMinutes(10);

    @Autowired
    private ProductRepository repo;

    @Autowired(required = false)
    private RedisTemplate<String, Product> redis;

    public Product save(Product product) {
        Product saved = repo.save(product);
        if (redis != null) {
            redis.opsForValue().set(CACHE_PREFIX + "id:" + saved.getId(), saved, CACHE_TTL);
            redis.opsForValue().set(CACHE_PREFIX + "name:" + saved.getName(), saved, CACHE_TTL);
        }
        return saved;
    }

    public List<Product> list(int limit) {
        return repo.findAll(PageRequest.of(0, limit)).stream().toList();
    }

    public Optional<Product> getById(Long id) {
        if (redis != null) {
            Product cached = redis.opsForValue().get(CACHE_PREFIX + "id:" + id);
            if (cached != null) return Optional.of(cached);
        }
        Optional<Product> found = repo.findById(id);
        found.ifPresent(p -> {
            if (redis != null) {
                redis.opsForValue().set(CACHE_PREFIX + "id:" + id, p, CACHE_TTL);
            }
        });
        return found;
    }

    public Optional<Product> getByName(String name) {
        if (redis != null) {
            Product cached = redis.opsForValue().get(CACHE_PREFIX + "name:" + name);
            if (cached != null) return Optional.of(cached);
        }
        Optional<Product> found = repo.findByName(name);
        found.ifPresent(p -> {
            if (redis != null) {
                redis.opsForValue().set(CACHE_PREFIX + "name:" + name, p, CACHE_TTL);
            }
        });
        return found;
    }
}
