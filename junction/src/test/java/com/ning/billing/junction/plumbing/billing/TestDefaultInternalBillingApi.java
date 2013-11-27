/*
 * Copyright 2010-2013 Ning, Inc.
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

package com.ning.billing.junction.plumbing.billing;

import java.util.List;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.billing.account.api.Account;
import com.ning.billing.api.TestApiListener.NextEvent;
import com.ning.billing.callcontext.InternalCallContext;
import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.PlanPhaseSpecifier;
import com.ning.billing.catalog.api.PriceListSet;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.entitlement.EntitlementService;
import com.ning.billing.entitlement.api.BlockingStateType;
import com.ning.billing.entitlement.api.DefaultEntitlementApi;
import com.ning.billing.entitlement.api.Entitlement;
import com.ning.billing.junction.BillingEvent;
import com.ning.billing.junction.DefaultBlockingState;
import com.ning.billing.junction.JunctionTestSuiteWithEmbeddedDB;
import com.ning.billing.subscription.api.SubscriptionBase;
import com.ning.billing.subscription.api.SubscriptionBaseTransitionType;

import com.google.common.collect.ImmutableList;

public class TestDefaultInternalBillingApi extends JunctionTestSuiteWithEmbeddedDB {

    // See https://github.com/killbill/killbill/issues/123
    //
    // Pierre, why do we have invocationCount > 0 here?
    //
    // This test will exercise ProxyBlockingStateDao#BLOCKING_STATE_ORDERING - unfortunately, for some reason,
    // the ordering doesn't seem deterministic. In some scenarii,
    // BlockingState(idA, effectiveDate1, BLOCK), BlockingState(idA, effectiveDate2, CLEAR), BlockingState(idB, effectiveDate2, BLOCK), BlockingState(idB, effectiveDate3, CLEAR)
    // is ordered
    // BlockingState(idA, effectiveDate1, BLOCK), BlockingState(idB, effectiveDate2, BLOCK), BlockingState(idA, effectiveDate2, CLEAR), BlockingState(idB, effectiveDate3, CLEAR)
    // The code BlockingCalculator#createBlockingDurations has been updated to support it, but we still want to make sure it actually works in both scenarii
    // (invocationCount = 10 will trigger both usecases in my testing).
    @Test(groups = "slow", description = "Check blocking states with same effective date are correctly handled", invocationCount = 10)
    public void testBlockingStatesWithSameEffectiveDate() throws Exception {
        final LocalDate initialDate = new LocalDate(2013, 8, 7);
        clock.setDay(initialDate);

        final Account account = accountApi.createAccount(getAccountData(7), callContext);
        final InternalCallContext internalCallContext = internalCallContextFactory.createInternalCallContext(account.getId(), callContext);

        testListener.pushExpectedEvent(NextEvent.CREATE);
        final PlanPhaseSpecifier spec = new PlanPhaseSpecifier("Shotgun", ProductCategory.BASE, BillingPeriod.MONTHLY, PriceListSet.DEFAULT_PRICELIST_NAME, null);
        final Entitlement entitlement = entitlementApi.createBaseEntitlement(account.getId(), spec, account.getExternalKey(), initialDate, callContext);
        final SubscriptionBase subscription = subscriptionInternalApi.getSubscriptionFromId(entitlement.getId(), internalCallContext);
        assertListenerStatus();

        final DateTime block1Date = clock.getUTCNow();
        testListener.pushExpectedEvents(NextEvent.BLOCK, NextEvent.BLOCK);
        final DefaultBlockingState state1 = new DefaultBlockingState(account.getId(),
                                                                     BlockingStateType.ACCOUNT,
                                                                     DefaultEntitlementApi.ENT_STATE_BLOCKED,
                                                                     EntitlementService.ENTITLEMENT_SERVICE_NAME,
                                                                     true,
                                                                     true,
                                                                     true,
                                                                     block1Date);
        blockingInternalApi.setBlockingState(state1, internalCallContext);
        // Same date, we'll order by record id asc
        final DefaultBlockingState state2 = new DefaultBlockingState(account.getId(),
                                                                     BlockingStateType.ACCOUNT,
                                                                     DefaultEntitlementApi.ENT_STATE_CLEAR,
                                                                     EntitlementService.ENTITLEMENT_SERVICE_NAME,
                                                                     false,
                                                                     false,
                                                                     false,
                                                                     block1Date);
        blockingInternalApi.setBlockingState(state2, internalCallContext);
        assertListenerStatus();

        clock.addDays(5);

        final DateTime block2Date = clock.getUTCNow();
        testListener.pushExpectedEvents(NextEvent.BLOCK, NextEvent.BLOCK);
        final DefaultBlockingState state3 = new DefaultBlockingState(entitlement.getBundleId(),
                                                                     BlockingStateType.SUBSCRIPTION_BUNDLE,
                                                                     DefaultEntitlementApi.ENT_STATE_BLOCKED,
                                                                     EntitlementService.ENTITLEMENT_SERVICE_NAME,
                                                                     true,
                                                                     true,
                                                                     true,
                                                                     block2Date);
        blockingInternalApi.setBlockingState(state3, internalCallContext);
        // Same date, we'll order by record id asc
        final DefaultBlockingState state4 = new DefaultBlockingState(entitlement.getBundleId(),
                                                                     BlockingStateType.SUBSCRIPTION_BUNDLE,
                                                                     DefaultEntitlementApi.ENT_STATE_CLEAR,
                                                                     EntitlementService.ENTITLEMENT_SERVICE_NAME,
                                                                     false,
                                                                     false,
                                                                     false,
                                                                     block2Date);
        blockingInternalApi.setBlockingState(state4, internalCallContext);
        assertListenerStatus();

        final DateTime block3Date = block2Date.plusDays(3);

        // Pass the phase
        testListener.pushExpectedEvent(NextEvent.PHASE);
        clock.addDays(50);
        assertListenerStatus();

        final DateTime block4Date = clock.getUTCNow();
        final DateTime block5Date = block4Date.plusDays(3);
        // Only one event on the bus (for state5)
        testListener.pushExpectedEvents(NextEvent.BLOCK);
        // Insert the clear state first, to make sure the order in which we insert blocking states doesn't matter
        // Since we are already in an ENT_STATE_CLEAR state for service ENTITLEMENT_SERVICE_NAME, we need to use a different
        // state name to simulate this behavior (otherwise, by design, this event won't be created)
        final DefaultBlockingState state6 = new DefaultBlockingState(entitlement.getBundleId(),
                                                                     BlockingStateType.SUBSCRIPTION_BUNDLE,
                                                                     DefaultEntitlementApi.ENT_STATE_CLEAR + "-something",
                                                                     EntitlementService.ENTITLEMENT_SERVICE_NAME,
                                                                     false,
                                                                     false,
                                                                     false,
                                                                     block5Date);
        blockingInternalApi.setBlockingState(state6, internalCallContext);
        final DefaultBlockingState state5 = new DefaultBlockingState(entitlement.getBundleId(),
                                                                     BlockingStateType.SUBSCRIPTION_BUNDLE,
                                                                     DefaultEntitlementApi.ENT_STATE_BLOCKED + "-something",
                                                                     EntitlementService.ENTITLEMENT_SERVICE_NAME,
                                                                     true,
                                                                     true,
                                                                     true,
                                                                     block4Date);
        blockingInternalApi.setBlockingState(state5, internalCallContext);
        assertListenerStatus();

        // Now, add back blocking states at an earlier date, for a different blockable id, to make sure the effective
        // date ordering is correctly respected when computing blocking durations
        testListener.pushExpectedEvents(NextEvent.BLOCK, NextEvent.BLOCK);
        final DefaultBlockingState state7 = new DefaultBlockingState(account.getId(),
                                                                     BlockingStateType.ACCOUNT,
                                                                     DefaultEntitlementApi.ENT_STATE_BLOCKED + "-something2",
                                                                     EntitlementService.ENTITLEMENT_SERVICE_NAME,
                                                                     true,
                                                                     true,
                                                                     true,
                                                                     block3Date);
        blockingInternalApi.setBlockingState(state7, internalCallContext);
        final DefaultBlockingState state8 = new DefaultBlockingState(account.getId(),
                                                                     BlockingStateType.ACCOUNT,
                                                                     DefaultEntitlementApi.ENT_STATE_CLEAR + "-something2",
                                                                     EntitlementService.ENTITLEMENT_SERVICE_NAME,
                                                                     false,
                                                                     false,
                                                                     false,
                                                                     block4Date);
        blockingInternalApi.setBlockingState(state8, internalCallContext);
        assertListenerStatus();

        // Advance for state6 to be active
        testListener.pushExpectedEvents(NextEvent.BLOCK);
        clock.addDays(5);
        assertListenerStatus();

        // Expected blocking durations:
        // * 2013-08-07 to 2013-08-07 (block1Date)
        // * 2013-08-12 to 2013-08-12 (block2Date)
        // * 2013-08-15 to 2013-10-04 [2013-08-15 to 2013-10-01 (block3Date -> block4Date) and 2013-10-01 to 2013-10-04 (block4Date -> block5Date)]
        final List<BillingEvent> events = ImmutableList.<BillingEvent>copyOf(billingInternalApi.getBillingEventsForAccountAndUpdateAccountBCD(account.getId(), internalCallContext));
        Assert.assertEquals(events.size(), 7);
        Assert.assertEquals(events.get(0).getTransitionType(), SubscriptionBaseTransitionType.CREATE);
        Assert.assertEquals(events.get(0).getEffectiveDate(), subscription.getStartDate());
        Assert.assertEquals(events.get(1).getTransitionType(), SubscriptionBaseTransitionType.START_BILLING_DISABLED);
        Assert.assertEquals(events.get(1).getEffectiveDate(), block1Date);
        Assert.assertEquals(events.get(2).getTransitionType(), SubscriptionBaseTransitionType.END_BILLING_DISABLED);
        Assert.assertEquals(events.get(2).getEffectiveDate(), block1Date);
        Assert.assertEquals(events.get(3).getTransitionType(), SubscriptionBaseTransitionType.START_BILLING_DISABLED);
        Assert.assertEquals(events.get(3).getEffectiveDate(), block2Date);
        Assert.assertEquals(events.get(4).getTransitionType(), SubscriptionBaseTransitionType.END_BILLING_DISABLED);
        Assert.assertEquals(events.get(4).getEffectiveDate(), block2Date);
        Assert.assertEquals(events.get(5).getTransitionType(), SubscriptionBaseTransitionType.START_BILLING_DISABLED);
        Assert.assertEquals(events.get(5).getEffectiveDate(), block3Date);
        Assert.assertEquals(events.get(6).getTransitionType(), SubscriptionBaseTransitionType.END_BILLING_DISABLED);
        Assert.assertEquals(events.get(6).getEffectiveDate(), block5Date);
    }
}
