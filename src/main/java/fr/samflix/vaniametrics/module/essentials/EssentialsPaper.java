package fr.samflix.vaniametrics.module.essentials;

import org.bukkit.plugin.java.JavaPlugin;

import fr.samflix.vaniametrics.api.Collector;
import fr.samflix.vaniametrics.api.VaniaMetrics;
import fr.samflix.vaniametrics.api.VaniaMetricsProvider;

/**
 * EssentialsX — inactivité des joueurs, et l'argent en circulation.
 *
 * <p>C'est lui qui répond à « combien d'argent y a-t-il sur le serveur », et il y répond sans rien parcourir : EssentialsX tient déjà le total dans un cache.
 *
 * <p>SON plugin.yml DÉCLARE {@code depend: [VaniaMetrics, Essentials]} : les deux sont
 * indispensables, et le déclarer laisse Bukkit garantir l'ordre de chargement plutôt que de
 * l'espérer. Retirer ce jar retire cette intégration et RIEN D'AUTRE — c'est tout l'intérêt d'un
 * jar par intégration.
 */
public final class EssentialsPaper extends JavaPlugin {

	private Collector collecteur;

	@Override
	public void onEnable() {
		VaniaMetrics metriques = VaniaMetricsProvider.get();
		collecteur = new EssentialsCollector(metriques.plateforme());
		metriques.enregistrer(collecteur);
	}

	@Override
	public void onDisable() {
		if (collecteur != null) {
			VaniaMetricsProvider.chercher().ifPresent(m -> m.retirer(collecteur));
		}
	}
}
