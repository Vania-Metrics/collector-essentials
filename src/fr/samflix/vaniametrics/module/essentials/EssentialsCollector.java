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
 * Le relevé EssentialsX.
 *
 * <p>EN FOND, à cause du classement des soldes : {@code calculateBalanceTopMapAsync()} lit tous
 * les comptes. On ne le DÉCLENCHE pas — EssentialsX le rafraîchit de lui-même — on lit son cache
 * et on publie son âge, pour qu'un total figé se voie au lieu de passer pour une économie stable.
 *
 * <p>LE COEFFICIENT DE GINI mérite son nom savant : il dit en un seul nombre si l'économie se
 * concentre chez trois joueurs. 0 = tout le monde a autant, 1 = un seul a tout. C'est le genre de
 * chose qu'un classement des dix premiers ne montre jamais.
 */
public final class EssentialsCollector implements Collector {

	private final Platform plateforme;

	private Gauge afk;
	private Histogram afkDuree;
	private Gauge total;
	private Gauge comptes;
	private Gauge quantiles;
	private Gauge gini;
	private Gauge ageCache;

	public EssentialsCollector(Platform plateforme) {
		this.plateforme = plateforme;
	}

	@Override
	public String nom() {
		return "essentials";
	}

	@Override
	public String origine() {
		return "EssentialsX";
	}

	@Override
	public boolean enFond() {
		return true;
	}

	@Override
	public long intervalleSecondes() {
		return 60;
	}

	@Override
	public void declarer(MetricRegistry r) {
		afk = r.gauge("server_players_afk", "Joueurs connectés mais inactifs.");
		afkDuree = r.histogram("server_players_afk_seconds",
				"Depuis combien de temps ils le sont. Distingue « trois joueurs partis deux "
						+ "minutes » de « trois joueurs partis six heures ».",
				Histogram.SECONDES_SESSION);
		total = r.gauge("economy_total",
				"Argent en circulation, tous comptes confondus.", "currency");
		comptes = r.gauge("economy_accounts", "Comptes ayant un solde.", "currency");
		quantiles = r.gauge("economy_balance",
				"Répartition des soldes. quantile = p50|p90|p99|max.", "currency", "quantile");
		gini = r.gauge("economy_gini",
				"Concentration de la richesse, de 0 (égalité) à 1 (un seul détenteur).", "currency");
		ageCache = r.gauge("economy_cache_age_seconds",
				"Âge du classement d'EssentialsX. S'il monte sans fin, le total affiché est figé.",
				"currency");
	}

	@Override
	public void relever(MetricRegistry r) {
		Essentials ess = (Essentials) Bukkit.getPluginManager().getPlugin("Essentials");
		if (ess == null) {
			return;
		}

		int inactifs = 0;
		long maintenant = System.currentTimeMillis();
		for (Player j : Bukkit.getOnlinePlayers()) {
			User u = ess.getUser(j);
			if (u != null && u.isAfk()) {
				inactifs++;
				long depuis = u.getAfkSince();
				if (depuis > 0) {
					afkDuree.observe((maintenant - depuis) / 1000.0);
				}
			}
		}
		afk.set(inactifs);

		BalanceTop classement = Bukkit.getServicesManager().load(BalanceTop.class);
		if (classement == null) {
			return;
		}
		// LE NOM RÉEL DE LA MONNAIE, pas « vault ». EssentialsX n'a qu'une économie — celle que
		// Vault arbitre — mais c'est ExcellentEconomy qui la fournit, sous un nom qui lui est
		// propre. Publier « vault » ferait deux étiquettes pour une même monnaie : ce total-ci
		// et les flux du module excellenteconomy ne se rejoindraient jamais dans un graphique.
		String monnaie = monnaieVault();
		ageCache.set(classement.getCacheAge() <= 0
				? Double.NaN
				: (maintenant - classement.getCacheAge()) / 1000.0, monnaie);

		BigDecimal somme = classement.getBalanceTopTotal();
		if (somme != null) {
			total.set(somme.doubleValue(), monnaie);
		}

		Map<java.util.UUID, BalanceTop.Entry> cache = classement.getBalanceTopCache();
		if (cache == null || cache.isEmpty()) {
			return;
		}
		double[] soldes = cache.values().stream()
				.mapToDouble(e -> e.getBalance().doubleValue())
				.sorted()
				.toArray();
		comptes.set(soldes.length, monnaie);
		quantiles.set(quantile(soldes, 0.50), monnaie, "p50");
		quantiles.set(quantile(soldes, 0.90), monnaie, "p90");
		quantiles.set(quantile(soldes, 0.99), monnaie, "p99");
		quantiles.set(soldes[soldes.length - 1], monnaie, "max");
		gini.set(gini(soldes), monnaie);
	}

	/**
	 * Le nom de la monnaie arbitrée par Vault, en minuscules.
	 *
	 * <p>Résolu à chaque relevé et non mis en cache : un plugin d'économie rechargé à chaud peut
	 * changer de monnaie principale, et le relevé est en fond toutes les minutes — ça ne coûte
	 * rien. « vault » en dernier recours, pour que l'étiquette ne soit jamais vide.
	 */
	@SuppressWarnings("deprecation")
	private String monnaieVault() {
		try {
			// L'API v1 DE VAULT, dépréciée par VaultUnlocked au profit de « vault2 », et c'est
			// pourtant elle qu'il faut : c'est celle qu'ExcellentEconomy ENREGISTRE. Viser la v2
			// rendrait un service absent, donc une monnaie « vault » permanente — un défaut plus
			// difficile à voir qu'un avertissement de compilation.
			Economy eco = Bukkit.getServicesManager().load(Economy.class);
			if (eco != null) {
				String nom = eco.currencyNameSingular();
				if (nom != null && !nom.isBlank()) {
					return nom.toLowerCase(java.util.Locale.ROOT);
				}
			}
		} catch (Throwable t) {
			plateforme.avertir("nom de monnaie Vault illisible — " + t);
		}
		return "vault";
	}

	private static double quantile(double[] triees, double p) {
		int i = (int) Math.min(triees.length - 1L, Math.round(p * (triees.length - 1)));
		return triees[i];
	}

	/**
	 * Gini sur un tableau DÉJÀ TRIÉ, par la formule de la moyenne pondérée par le rang.
	 *
	 * <p>Elle est en O(n) là où la définition — la moitié de l'écart absolu moyen — est en O(n²).
	 * Sur mille comptes la différence ne se verrait pas ; sur cent mille, si.
	 */
	private static double gini(double[] triees) {
		double somme = 0;
		double pondere = 0;
		for (int i = 0; i < triees.length; i++) {
			double v = Math.max(0, triees[i]);
			somme += v;
			pondere += (i + 1) * v;
		}
		if (somme <= 0) {
			return 0;
		}
		int n = triees.length;
		return (2 * pondere) / (n * somme) - (n + 1.0) / n;
	}
}
