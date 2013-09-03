/*
 * Copyright 2011 Greg Haines
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.greghaines.jesque.meta.dao.impl;

import static net.greghaines.jesque.utils.ResqueConstants.FAILED;
import static net.greghaines.jesque.utils.ResqueConstants.QUEUE;
import static net.greghaines.jesque.utils.ResqueConstants.QUEUES;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

import net.greghaines.jesque.Config;
import net.greghaines.jesque.Job;
import net.greghaines.jesque.JobFailure;
import net.greghaines.jesque.json.ObjectMapperFactory;
import net.greghaines.jesque.meta.dao.FailureDAO;
import net.greghaines.jesque.utils.JesqueUtils;
import net.greghaines.jesque.utils.PoolUtils;
import net.greghaines.jesque.utils.PoolUtils.PoolWork;

import redis.clients.jedis.Jedis;
import redis.clients.util.Pool;

public class FailureDAORedisImpl implements FailureDAO {
    
    private final Config config;
    private final Pool<Jedis> jedisPool;

    public FailureDAORedisImpl(final Config config, final Pool<Jedis> jedisPool) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (jedisPool == null) {
            throw new IllegalArgumentException("jedisPool must not be null");
        }
        this.config = config;
        this.jedisPool = jedisPool;
    }

    public long getCount() {
        return PoolUtils.doWorkInPoolNicely(this.jedisPool, new PoolWork<Jedis, Long>() {
            public Long doWork(final Jedis jedis) throws Exception {
                return jedis.llen(key(FAILED));
            }
        });
    }

    public List<JobFailure> getFailures(final long offset, final long count) {
        return PoolUtils.doWorkInPoolNicely(this.jedisPool, new PoolWork<Jedis, List<JobFailure>>() {
            public List<JobFailure> doWork(final Jedis jedis) throws Exception {
                final List<String> payloads = jedis.lrange(key(FAILED), offset, offset + count - 1);
                final List<JobFailure> failures = new ArrayList<JobFailure>(payloads.size());
                for (final String payload : payloads) {
                    failures.add(ObjectMapperFactory.get().readValue(payload, JobFailure.class));
                }
                return failures;
            }
        });
    }

    public void clear() {
        PoolUtils.doWorkInPoolNicely(this.jedisPool, new PoolWork<Jedis, Void>() {
            public Void doWork(final Jedis jedis) throws Exception {
                jedis.del(key(FAILED));
                return null;
            }
        });
    }

    public Date requeue(final long index) {
        final List<JobFailure> failures = getFailures(index, 1);
        return (failures.isEmpty()) ? null : PoolUtils.doWorkInPoolNicely(this.jedisPool, new PoolWork<Jedis, Date>() {
            public Date doWork(final Jedis jedis) throws Exception {
                final Date retriedAt = new Date();
                final JobFailure failure = failures.get(0);
                failure.setRetriedAt(retriedAt);
                jedis.lset(key(FAILED), (int) index, ObjectMapperFactory.get().writeValueAsString(failure));
                enqueue(jedis, failure.getQueue(), failure.getPayload());
                return retriedAt;
            }
        });
    }

    public void remove(final long index) {
        PoolUtils.doWorkInPoolNicely(this.jedisPool, new PoolWork<Jedis, Void>() {
            public Void doWork(final Jedis jedis) throws Exception {
                final String failedKey = key(FAILED);
                final String randId = Integer.toString(new Random().nextInt());
                jedis.lset(failedKey, index, randId);
                jedis.lrem(failedKey, 1, randId);
                return null;
            }
        });
    }

    private void enqueue(final Jedis jedis, final String queue, final Job job) throws IOException {
        if (queue == null || "".equals(queue)) {
            throw new IllegalArgumentException("queue must not be null or empty: " + queue);
        }
        if (job == null) {
            throw new IllegalArgumentException("job must not be null");
        }
        if (!job.isValid()) {
            throw new IllegalStateException("job is not valid: " + job);
        }
        final String msg = ObjectMapperFactory.get().writeValueAsString(job);
        jedis.sadd(key(QUEUES), queue);
        jedis.rpush(key(QUEUE, queue), msg);
    }

    /**
     * Builds a namespaced Redis key with the given arguments.
     * 
     * @param parts
     *            the key parts to be joined
     * @return an assembled String key
     */
    private String key(final String... parts) {
        return JesqueUtils.createKey(this.config.getNamespace(), parts);
    }
}
