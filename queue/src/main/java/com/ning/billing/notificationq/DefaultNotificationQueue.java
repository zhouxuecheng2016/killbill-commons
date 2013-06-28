/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.notificationq;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joda.time.DateTime;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.sqlobject.mixins.Transmogrifier;

import com.ning.billing.Hostname;
import com.ning.billing.notificationq.NotificationQueueService.NotificationQueueHandler;
import com.ning.billing.notificationq.dao.NotificationSqlDao;
import com.ning.billing.queue.DefaultQueueLifecycle;
import com.ning.billing.util.clock.Clock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Objects;

public class DefaultNotificationQueue implements NotificationQueue {

    private final NotificationSqlDao dao;
    private final String svcName;
    private final String queueName;
    private final NotificationQueueHandler handler;
    private final NotificationQueueService notificationQueueService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private volatile boolean isStarted;

    public DefaultNotificationQueue(final String svcName, final String queueName, final NotificationQueueHandler handler,
                                    final IDBI dbi, final NotificationQueueService notificationQueueService,
                                    final Clock clock) {
        this.svcName = svcName;
        this.queueName = queueName;
        this.handler = handler;
        this.dao = dbi.onDemand(NotificationSqlDao.class);
        this.notificationQueueService = notificationQueueService;
        this.objectMapper = new ObjectMapper();
        this.clock = clock;
    }


    @Override
    public void recordFutureNotification(final DateTime futureNotificationTime, final NotificationKey eventJson, final UUID userToken, final Long searchKey1, final Long searchKey2) throws IOException {
        recordFutureNotificationInternal(futureNotificationTime, eventJson, dao, userToken, searchKey1, searchKey2);

    }

    @Override
    public void recordFutureNotificationFromTransaction(final Transmogrifier transmogrifier, final DateTime futureNotificationTime, final NotificationKey eventJson,
                                                        final UUID userToken, final Long searchKey1, final Long searchKey2) throws IOException {
        final NotificationSqlDao transactionalNotificationDao = transmogrifier.become(NotificationSqlDao.class);
        recordFutureNotificationInternal(futureNotificationTime, eventJson, transactionalNotificationDao, userToken, searchKey1, searchKey2);
    }



    private void recordFutureNotificationInternal(final DateTime futureNotificationTime,
                                                  final NotificationKey eventJson,
                                                  final NotificationSqlDao thisDao,
                                                  final UUID userToken, final Long searchKey1, final Long searchKey2) throws IOException {
        final String json = objectMapper.writeValueAsString(eventJson);
        final UUID futureUserToken = UUID.randomUUID();
        final Long searchKey2WithNull =  Objects.firstNonNull(searchKey2, new Long(0));
        final Notification notification = new DefaultNotification(getFullQName(), Hostname.get(), eventJson.getClass().getName(), json,
                                                                  userToken, futureUserToken, futureNotificationTime, searchKey1, searchKey2WithNull);
        thisDao.insertNotification(notification);
    }

    @Override
    public  <T extends NotificationKey> Map<Notification, T> getFutureNotificationsForAccountAndType(final Class<T> type, final Long searchKey1) {
        return getFutureNotificationsForAccountInternal(type, dao, searchKey1);
    }

    @Override
    public <T extends NotificationKey> Map<Notification, T> getFutureNotificationsForAccountAndTypeFromTransaction(final Class<T> type, final Long searchKey1, final Transmogrifier transmogrifier) {
        final NotificationSqlDao transactionalNotificationDao = transmogrifier.become(NotificationSqlDao.class);
        return getFutureNotificationsForAccountInternal(type, transactionalNotificationDao, searchKey1);
    }


    private <T extends NotificationKey> Map<Notification, T> getFutureNotificationsForAccountInternal(final Class<T> type, final NotificationSqlDao transactionalDao, final long searchKey1) {

        List<Notification> notifications =  transactionalDao.getFutureNotificationsForAccount(clock.getUTCNow().toDate(), getFullQName(), searchKey1);

        Map<Notification, T> result = new HashMap<Notification, T>(notifications.size());
        for (Notification cur : notifications) {
            if (type.getName().equals(cur.getNotificationKeyClass())) {
                result.put(cur, (T) DefaultQueueLifecycle.deserializeEvent(cur.getNotificationKeyClass(), objectMapper, cur.getNotificationKey()));
            }
        }
        return result;
    }


    @Override
    public void removeNotification(final Long recordId) {
        dao.removeNotification(recordId);
    }

    @Override
    public void removeNotificationFromTransaction(final Transmogrifier transmogrifier, final Long recordId) {
        final NotificationSqlDao transactionalNotificationDao = transmogrifier.become(NotificationSqlDao.class);
        transactionalNotificationDao.removeNotification(recordId);
    }


    @Override
    public String getFullQName() {
        return NotificationQueueServiceBase.getCompositeName(svcName, queueName);
    }

    @Override
    public String getServiceName() {
        return svcName;
    }

    @Override
    public String getQueueName() {
        return queueName;
    }

    @Override
    public String getHostName() {
        return null;
    }

    @Override
    public NotificationQueueHandler getHandler() {
        return handler;
    }

    @Override
    public void startQueue() {
        notificationQueueService.startQueue();
        isStarted = true;
    }

    @Override
    public void stopQueue() {
        // Order matters...
        isStarted = false;
        notificationQueueService.stopQueue();
    }

    @Override
    public boolean isStarted() {
        return isStarted;
    }

}