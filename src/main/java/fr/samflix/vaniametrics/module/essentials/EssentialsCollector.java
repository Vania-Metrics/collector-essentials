package fr.samflix.vaniametrics.module.essentials;

import java.math.BigDecimal;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;

import net.milkbowl.vault.economy.Economy;

import net.essentialsx.api.v2.services.BalanceTop;

import fr.samflix.vaniametrics.api.Collector;
import fr.samflix.vaniametrics.api.Gauge;
import fr.samflix.vaniametrics.api.Histogram;
import fr.samflix.vaniametrics.api.MetricRegistry;
import fr.samflix.vaniametrics.api.Platform;

/**
 * EssentialsX collector.
 *
 * <p>Runs in the background because of the balance top ranking: {@code
 * calculateBalanceTopMapAsync()} reads every account. We don't trigger it — EssentialsX
 * refreshes it on its own — we read its cache and publish its age, so a stale total shows up
 * instead of passing for a stable economy.
 *
 * <p>The Gini coefficient says in one number whether the economy is concentrated in a handful of
 * players. 0 = everyone has the same, 1 = one player has everything. It's the kind of thing a
 * top-ten ranking never shows.
 */
public final class EssentialsCollector implements Collector {

	private final Platform platform;

	private Gauge afk;
	private Histogram afkSeconds;
	private Gauge total;
	private Gauge accounts;
	private Gauge quantiles;
	private Gauge gini;
	private Gauge cacheAge;

	public EssentialsCollector(Platform platform) {
		this.platform = platform;
	}

	@Override
	public String name() {
		return "essentials";
	}

	@Override
	public String source() {
		return "EssentialsX";
	}

	@Override
	public boolean isBackground() {
		return true;
	}

	@Override
	public long intervalSeconds() {
		return 60;
	}

	@Override
	public void declare(MetricRegistry r) {
		afk = r.gauge("server_players_afk", "Players connected but idle.");
		afkSeconds = r.histogram("server_players_afk_seconds",
				"How long they've been idle. Distinguishes \"three players away for two "
						+ "minutes\" from \"three players away for six hours\".",
				Histogram.SESSION_SECONDS);
		total = r.gauge("economy_total",
				"Money in circulation, across all accounts.", "currency");
		accounts = r.gauge("economy_accounts", "Accounts with a balance.", "currency");
		quantiles = r.gauge("economy_balance",
				"Balance distribution. quantile = p50|p90|p99|max.", "currency", "quantile");
		gini = r.gauge("economy_gini",
				"Wealth concentration, from 0 (equal) to 1 (one holder).", "currency");
		cacheAge = r.gauge("economy_cache_age_seconds",
				"Age of EssentialsX's ranking cache. If it keeps rising, the displayed total is stale.",
				"currency");
	}

	@Override
	public void collect(MetricRegistry r) {
		Essentials ess = (Essentials) Bukkit.getPluginManager().getPlugin("Essentials");
		if (ess == null) {
			return;
		}

		int idle = 0;
		long now = System.currentTimeMillis();
		for (Player p : Bukkit.getOnlinePlayers()) {
			User u = ess.getUser(p);
			if (u != null && u.isAfk()) {
				idle++;
				long since = u.getAfkSince();
				if (since > 0) {
					afkSeconds.observe((now - since) / 1000.0);
				}
			}
		}
		afk.set(idle);

		BalanceTop top = Bukkit.getServicesManager().load(BalanceTop.class);
		if (top == null) {
			return;
		}
		// The actual currency name, not "vault". EssentialsX has only one economy — the one
		// Vault arbitrates — but ExcellentEconomy is the one providing it, under its own name.
		// Publishing "vault" would create two labels for the same currency: this total and the
		// excellenteconomy module's flows would never join up in a graph.
		String currency = vaultCurrency();
		cacheAge.set(top.getCacheAge() <= 0
				? Double.NaN
				: (now - top.getCacheAge()) / 1000.0, currency);

		BigDecimal sum = top.getBalanceTopTotal();
		if (sum != null) {
			total.set(sum.doubleValue(), currency);
		}

		Map<java.util.UUID, BalanceTop.Entry> cache = top.getBalanceTopCache();
		if (cache == null || cache.isEmpty()) {
			return;
		}
		double[] balances = cache.values().stream()
				.mapToDouble(e -> e.getBalance().doubleValue())
				.sorted()
				.toArray();
		accounts.set(balances.length, currency);
		quantiles.set(quantile(balances, 0.50), currency, "p50");
		quantiles.set(quantile(balances, 0.90), currency, "p90");
		quantiles.set(quantile(balances, 0.99), currency, "p99");
		quantiles.set(balances[balances.length - 1], currency, "max");
		gini.set(gini(balances), currency);
	}

	/**
	 * The name of the currency Vault arbitrates, lowercased.
	 *
	 * <p>Resolved on every collect and not cached: a hot-reloaded economy plugin can change its
	 * primary currency, and the collector runs in the background every minute — it costs
	 * nothing. "vault" as a last resort, so the label is never empty.
	 */
	@SuppressWarnings("deprecation")
	private String vaultCurrency() {
		try {
			// Vault's v1 API, deprecated by VaultUnlocked in favor of "vault2", and yet it's
			// the one to use: it's the one ExcellentEconomy registers. Targeting v2 would find
			// no service, so a permanent "vault" currency — a defect harder to spot than a
			// compiler warning.
			Economy eco = Bukkit.getServicesManager().load(Economy.class);
			if (eco != null) {
				String name = eco.currencyNameSingular();
				if (name != null && !name.isBlank()) {
					return name.toLowerCase(java.util.Locale.ROOT);
				}
			}
		} catch (Throwable t) {
			platform.warn("could not read Vault currency name — " + t);
		}
		return "vault";
	}

	private static double quantile(double[] sorted, double p) {
		int i = (int) Math.min(sorted.length - 1L, Math.round(p * (sorted.length - 1)));
		return sorted[i];
	}

	/**
	 * Gini on an already-sorted array, using the rank-weighted mean formula.
	 *
	 * <p>This is O(n) where the definition — half the mean absolute difference — is O(n^2). On a
	 * thousand accounts the difference wouldn't show; on a hundred thousand, it would.
	 */
	private static double gini(double[] sorted) {
		double sum = 0;
		double weighted = 0;
		for (int i = 0; i < sorted.length; i++) {
			double v = Math.max(0, sorted[i]);
			sum += v;
			weighted += (i + 1) * v;
		}
		if (sum <= 0) {
			return 0;
		}
		int n = sorted.length;
		return (2 * weighted) / (n * sum) - (n + 1.0) / n;
	}
}
