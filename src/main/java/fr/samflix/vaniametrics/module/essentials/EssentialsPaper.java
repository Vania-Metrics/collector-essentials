package fr.samflix.vaniametrics.module.essentials;

import org.bukkit.plugin.java.JavaPlugin;

import fr.samflix.vaniametrics.api.Collector;
import fr.samflix.vaniametrics.api.VaniaMetrics;
import fr.samflix.vaniametrics.api.VaniaMetricsProvider;

/**
 * EssentialsX — player inactivity, and money in circulation.
 *
 * <p>Answers "how much money is on the server" without scanning anything: EssentialsX already
 * keeps the total in a cache.
 *
 * <p>Its plugin.yml declares {@code depend: [VaniaMetrics, Essentials]}: both are required, and
 * declaring it lets Bukkit guarantee load order instead of hoping for it. Removing this jar
 * removes this integration and nothing else — that's the point of one jar per integration.
 */
public final class EssentialsPaper extends JavaPlugin {

	private Collector collector;

	@Override
	public void onEnable() {
		VaniaMetrics metrics = VaniaMetricsProvider.get();
		collector = new EssentialsCollector(metrics.platform());
		metrics.register(collector);
	}

	@Override
	public void onDisable() {
		if (collector != null) {
			VaniaMetricsProvider.find().ifPresent(m -> m.unregister(collector));
		}
	}
}
