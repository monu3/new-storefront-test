package com.storefront.store.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.storefront.store.catalog.ShopType;
import com.storefront.store.identity.AppUser;
import com.storefront.store.identity.AppUserRepository;
import com.storefront.store.shop.Shop;
import com.storefront.store.shop.ShopRepository;
import com.storefront.store.support.TestConfig;
import com.storefront.store.support.TestFixtures;

/**
 * Following a shop, and the counter that goes with it (D13).
 *
 * <p>The property worth the most attention: <b>the counter only moves when a row was actually
 * created.</b> Following twice is common — a double-tap, a retry — and incrementing
 * unconditionally is how a follower count drifts above the number of real followers. A count that
 * overstates a shop's audience is worse than one that is imprecise, because the owner trusts it.
 *
 * <p>The tests assert against {@code countOf}, which counts the rows, rather than against the
 * cached column. Asserting a cache against itself proves nothing.
 */
@SpringBootTest
@Transactional
@ActiveProfiles("test")
@Import(TestConfig.class)
class FollowerServiceIT {

	@Autowired
	private FollowerService followers;

	@Autowired
	private ShopRepository shops;

	@Autowired
	private ShopFollowerRepository rows;

	@Autowired
	private AppUserRepository users;

	@Autowired
	private TestFixtures fixtures;

	private AppUser customer() {
		return users.saveAndFlush(new AppUser(AppUser.Realm.CUSTOMERS,
				"cust-" + UUID.randomUUID(),
				"cust-" + UUID.randomUUID() + "@example.test", "Customer"));
	}

	private Shop shop() {
		return fixtures.createVerifiedShop(
				"Followed " + UUID.randomUUID().toString().substring(0, 6), ShopType.GROCERY);
	}

	@Nested
	@DisplayName("following")
	class Following {

		@Test
		@DisplayName("creates a row and increments the counter")
		void follows() {
			Shop shop = shop();
			AppUser user = customer();

			assertThat(followers.follow(shop.getId(), user.getId())).isTrue();

			assertThat(followers.countOf(shop.getId())).isEqualTo(1L);
			assertThat(shops.findById(shop.getId()).orElseThrow().getFollowerCount()).isEqualTo(1);
		}

		@Test
		@DisplayName("is idempotent: following twice does not inflate the count")
		void followingTwiceIsOne() {
			// The whole point. A count that overstates an audience is worse than one that is
			// imprecise, because the owner trusts it.
			Shop shop = shop();
			AppUser user = customer();

			followers.follow(shop.getId(), user.getId());
			boolean second = followers.follow(shop.getId(), user.getId());

			assertThat(second).as("the second follow changed nothing").isFalse();
			assertThat(followers.countOf(shop.getId())).isEqualTo(1L);
			assertThat(shops.findById(shop.getId()).orElseThrow().getFollowerCount())
					.as("the cached counter must agree with the rows")
					.isEqualTo(1);
		}

		@Test
		@DisplayName("two different customers count twice")
		void distinctCustomers() {
			Shop shop = shop();

			followers.follow(shop.getId(), customer().getId());
			followers.follow(shop.getId(), customer().getId());

			assertThat(followers.countOf(shop.getId())).isEqualTo(2L);
			assertThat(shops.findById(shop.getId()).orElseThrow().getFollowerCount()).isEqualTo(2);
		}

		@Test
		@DisplayName("a shop's counter is unaffected by another shop's follows")
		void countersAreIndependent() {
			Shop first = shop();
			Shop second = shop();

			followers.follow(first.getId(), customer().getId());

			assertThat(shops.findById(second.getId()).orElseThrow().getFollowerCount()).isZero();
		}

		@Test
		@DisplayName("refuses to follow a shop that does not exist")
		void refusesUnknownShop() {
			assertThatThrownBy(() -> followers.follow(UUID.randomUUID(), customer().getId()))
					.isInstanceOf(IllegalArgumentException.class);
		}

		@Test
		@DisplayName("refuses nulls rather than creating a row with a missing half")
		void refusesNulls() {
			Shop shop = shop();
			assertThatThrownBy(() -> followers.follow(shop.getId(), null))
					.isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> followers.follow(null, customer().getId()))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Nested
	@DisplayName("unfollowing")
	class Unfollowing {

		@Test
		@DisplayName("removes the row and decrements the counter")
		void unfollows() {
			Shop shop = shop();
			AppUser user = customer();
			followers.follow(shop.getId(), user.getId());

			assertThat(followers.unfollow(shop.getId(), user.getId())).isTrue();

			assertThat(followers.countOf(shop.getId())).isZero();
			assertThat(shops.findById(shop.getId()).orElseThrow().getFollowerCount()).isZero();
		}

		@Test
		@DisplayName("is idempotent, and the counter never goes negative")
		void unfollowingTwiceIsHarmless() {
			Shop shop = shop();
			AppUser user = customer();
			followers.follow(shop.getId(), user.getId());
			followers.unfollow(shop.getId(), user.getId());

			boolean second = followers.unfollow(shop.getId(), user.getId());

			assertThat(second).isFalse();
			assertThat(shops.findById(shop.getId()).orElseThrow().getFollowerCount())
					.as("a double unfollow must not drive the counter below zero")
					.isZero();
		}

		@Test
		@DisplayName("unfollowing without having followed changes nothing")
		void unfollowWithoutFollow() {
			Shop shop = shop();

			assertThat(followers.unfollow(shop.getId(), customer().getId())).isFalse();
			assertThat(shops.findById(shop.getId()).orElseThrow().getFollowerCount()).isZero();
		}

		@Test
		@DisplayName("only affects the one customer who left")
		void otherFollowersRemain() {
			Shop shop = shop();
			AppUser leaving = customer();
			AppUser staying = customer();
			followers.follow(shop.getId(), leaving.getId());
			followers.follow(shop.getId(), staying.getId());

			followers.unfollow(shop.getId(), leaving.getId());

			assertThat(followers.countOf(shop.getId())).isEqualTo(1L);
			assertThat(followers.isFollowing(shop.getId(), staying.getId())).isTrue();
			assertThat(followers.isFollowing(shop.getId(), leaving.getId())).isFalse();
		}
	}

	@Nested
	@DisplayName("reconciliation")
	class Reconciliation {

		@Test
		@DisplayName("repairs a drifted counter from the rows")
		void repairsDrift() {
			// The cache exists so the gallery can sort without a subquery. This exists because it
			// can drift, and a drift that is invisible is the problem.
			Shop shop = shop();
			followers.follow(shop.getId(), customer().getId());
			followers.follow(shop.getId(), customer().getId());
			followers.follow(shop.getId(), customer().getId());

			// Force a drift the way a lost update would.
			Shop drifted = shops.findById(shop.getId()).orElseThrow();
			drifted.setFollowerCount(99);
			shops.saveAndFlush(drifted);

			followers.reconcileCounter(shop.getId());

			assertThat(shops.findById(shop.getId()).orElseThrow().getFollowerCount())
					.as("the cache is set back to the true row count")
					.isEqualTo(3);
		}

		@Test
		@DisplayName("the counter agrees with the rows after a normal sequence")
		void agreesAfterNormalUse() {
			Shop shop = shop();
			AppUser a = customer();
			AppUser b = customer();

			followers.follow(shop.getId(), a.getId());
			followers.follow(shop.getId(), a.getId());
			followers.follow(shop.getId(), b.getId());
			followers.unfollow(shop.getId(), a.getId());
			followers.unfollow(shop.getId(), a.getId());

			assertThat(shops.findById(shop.getId()).orElseThrow().getFollowerCount())
					.isEqualTo((int) followers.countOf(shop.getId()));
		}
	}

	@Nested
	@DisplayName("read models")
	class Reads {

		@Test
		@DisplayName("reports whether this customer follows")
		void reportsState() {
			Shop shop = shop();
			AppUser user = customer();

			assertThat(followers.isFollowing(shop.getId(), user.getId())).isFalse();
			followers.follow(shop.getId(), user.getId());
			assertThat(followers.isFollowing(shop.getId(), user.getId())).isTrue();
		}

		@Test
		@DisplayName("counts how many shops one customer follows")
		void countsCustomerFollows() {
			AppUser user = customer();
			followers.follow(shop().getId(), user.getId());
			followers.follow(shop().getId(), user.getId());

			assertThat(followers.countFollowersOf(user.getId())).isEqualTo(2L);
		}
	}
}
