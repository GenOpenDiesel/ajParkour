package us.ajg0702.parkour.game;

import fr.mrmicky.infinitejump.InfiniteJump;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import us.ajg0702.parkour.Main;
import us.ajg0702.parkour.Messages;
import us.ajg0702.parkour.Rewards;
import us.ajg0702.parkour.Scores;
import us.ajg0702.parkour.api.events.PlayerEndParkourEvent;
import us.ajg0702.parkour.api.events.PlayerJumpEvent;
import us.ajg0702.parkour.api.events.PlayerStartParkourEvent;
import us.ajg0702.parkour.utils.FoliaScheduler;
import us.ajg0702.parkour.utils.InvManager;
import us.ajg0702.parkour.utils.VersionSupport;
import us.ajg0702.utils.spigot.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PkPlayer implements Listener {
	
	long lastmove;
	
	Player ply;
	Manager man;
	PkArea area;
	
	Messages msgs;
	
	Config config;
	
	Scores scores;
	
	Main plugin;
	
	long started;
	
	List<PkJump> jumps;
	
	int score = 0;
	
	volatile boolean active = true;

	// true once the jumps have been generated/placed and the player teleported in.
	// Guards move/fall checks during the brief async setup window.
	volatile boolean ready = false;

	// ensures end() runs its cleanup exactly once
	private boolean ended = false;
	
	boolean teleporting = false;
	
	String block;
	
	int prevhigh = 0;
	
	int afkkick;
	FoliaScheduler.Task afktask;
	
	int ahead; // how many (extra) blocks to make ahead
	
	FoliaScheduler.Task clearPotsTask;
	
	boolean fasterAfkCheck;
	FoliaScheduler.Task fastAfkCheckTask;
	
	public boolean beatServerHighscore = false;
	
	List<String> cmds = new ArrayList<>();
	
	
	boolean infiniteJump = false;
	InfiniteJump ij;
	boolean ijenableAfter = false;

	/**
	 * Inits a parkour player
	 * @param p The {@link org.bukkit.entity.Player Player} that is in parkour
	 * @param m The {@link us.ajg0702.parkour.game.Manager Manager}.
	 * @param a The {@link us.ajg0702.parkour.game.PkArea PkArea} that the parkour should take place in.
	 */
	public PkPlayer(Player p, Manager m, PkArea a) {
		lastmove = System.currentTimeMillis();
		ply = p;
		man = m;
		area = a;
		plugin = m.main;
		msgs = m.msgs;
		
		ahead = plugin.config.getInt("jumps-ahead");
		
		scores = plugin.scores;
		
		config = plugin.getAConfig();
		
		block = plugin.selector.getBlock(p, area);
		
		
		
		started = System.currentTimeMillis();
		
		afkkick = config.getInt("kick-time");
		
		fasterAfkCheck = config.getBoolean("faster-afk-detection");
		
		FoliaScheduler.runAsync(plugin, () -> {
			prevhigh = scores.getHighScore(ply.getUniqueId(), config.getBoolean("begin-score-per-area") ? area.getName() : null);
			final int highScore = prevhigh;
			FoliaScheduler.runForEntity(plugin, p, () -> {
				if(highScore > 0 && !(highScore+"").equalsIgnoreCase("-1")) {
					p.sendMessage(msgs.get("start.score", p).replaceAll("\\{SCORE}", ""+ highScore));
				} else {
					p.sendMessage(msgs.get("start.first", p).replaceAll("\\{SCORE}", ""+ highScore));
				}
			});
		});
		
		
		FoliaScheduler.runAsync(plugin, () -> plugin.scores.addToGamesPlayed(p.getUniqueId()));
		
		
		if(!fasterAfkCheck) {
			if(afkkick > 0) {
				Bukkit.getServer().getPluginManager().registerEvents(this, plugin);
			}
		} else if(afkkick > 0) {
			fastAfkCheckTask = FoliaScheduler.runTimerForEntity(plugin, ply, () -> onMove(new PlayerMoveEvent(ply, p.getLocation(), p.getLocation())), 5, 5);
		}
		
		infiniteJump = Bukkit.getPluginManager().getPlugin("InfiniteJump") != null;
		if(infiniteJump) {
			 ij = (InfiniteJump) Bukkit.getPluginManager().getPlugin("InfiniteJump");
		}
		
		// Capture orientation on the player's own thread. Block generation/placement must run on
		// the region thread that owns the parkour area, so we can't read the player's location there.
		final float yaw = p.getLocation().getYaw();
		final float pitch = p.getLocation().getPitch();
		teleporting = true;
		jumps = new ArrayList<>();
		FoliaScheduler.runAtLocation(plugin, area.getPos1(), () -> {
			if(!active) return; // player already left/ended during setup
			Location start = area.getRandomPosition();
			jumps.add(new PkJump(this, start, yaw));
			Location prevJump = jumps.get(jumps.size()-1).getFrom();
			jumps.add(new PkJump(this, prevJump, yaw));
			jumps.get(0).place();
			int i = 0;
			while(i < ahead) {
				i++;
				jumps.add(new PkJump(this, jumps.get(jumps.size()-1).getFrom(), yaw));
				jumps.get(jumps.size()-1).place();
			}

			for(PkJump jump : jumps) {
				if(!jump.isPlaced()) {
					jump.place();
				}
			}
			Location tp = jumps.get(0).getTo();
			final Location dest = new Location(tp.getWorld(), tp.getX()+0.5, tp.getY()+1.5, tp.getZ()+0.5, yaw, pitch);
			FoliaScheduler.runForEntity(plugin, ply, () -> {
				if(!active) return;
				FoliaScheduler.teleport(ply, dest);
				FoliaScheduler.runDelayedForEntity(m.main, ply, () -> teleporting = false, 5);
				playSound("start-sound", ply);
				ready = true;
				Bukkit.getPluginManager().callEvent(new PlayerStartParkourEvent(this));
			});
		});
		
		
		if(config.getBoolean("parkour-inventory")) {
			try {
				InvManager.saveInventory(ply);
				ply.getInventory().clear();
				boolean requirePerm = config.getBoolean("require-permission-for-block-selector-item");
				if(config.getBoolean("enable-block-selector-item") &&
						(!requirePerm || (requirePerm && ply.getPlayer().hasPermission("ajparkour.selector")))) {
					ItemStack bsItem = new ItemStack(Material.CHEST, 1); // bs = block selector
					ItemMeta bsMeta = bsItem.getItemMeta();
					bsMeta.setDisplayName(msgs.get("items.blockselector.name"));
					bsItem.setItemMeta(bsMeta);
					ply.getInventory().setItem(4, bsItem);
				}
				
			} catch (IOException e) {
				ply.sendMessage("&cAn error occured while trying to save your inventory!");
				Bukkit.getLogger().severe("[ajParkour] An error occured while trying to save player's inventory:");
				e.printStackTrace();
			}
		}
		
		if(afkkick >= 0) {
			afktask = FoliaScheduler.runTimerForEntity(plugin, ply, () -> {
				long distance = System.currentTimeMillis() - lastmove;
				if(distance > (afkkick* 1000L)) {
					end(msgs.get("fall.force.afk"));
				}
			}, afkkick* 20L, 20);
		}
		
		clearPots();
		clearPotsTask = FoliaScheduler.runTimerForEntity(plugin, ply, () -> {
			if(Manager.getInstance().getPlayer(ply) != null) {
				clearPots();
			}
		}, 0, 20);
		
		
		if(infiniteJump) {
			if(ij.getJumpManager().isActive(ply)) {
				ij.getJumpManager().disable(ply);
				ijenableAfter = true;
			}
		}
	}
	
	private final List<PotionEffectType> disallowedPots = Arrays.asList(
			PotionEffectType.SPEED,
			PotionEffectType.JUMP,
			PotionEffectType.getByName("LEVITATION"),
			PotionEffectType.getByName("SLOW_FALLING"));
	private void clearPots() {
		for(PotionEffect effect : ply.getActivePotionEffects()) {
	        if(disallowedPots.contains(effect.getType())) {
	        	ply.removePotionEffect(effect.getType());
	        }
	    }
	}
	
	
	private void madeIt() {
		score++;
		jumps.get(0).remove();
		jumps.remove(0);
		Location prevJump = jumps.get(jumps.size()-1).getFrom();
		//ply.sendMessage(AreaStorage.coordsString(prevJump));
		PkJump nj = new PkJump(this, prevJump, ply.getLocation().getYaw());
		nj.place();
		jumps.add(nj);
		VersionSupport.sendActionBar(ply, 
				msgs.get("score")
				.replaceAll("\\{SCORE}", score+"")
				.replaceAll("\\{HIGHSCORE}", prevhigh < 0 ? "0" : prevhigh+"")
				);
		
		if(score == prevhigh && prevhigh > 0) {
			ply.sendMessage(msgs.get("beatrecord-ingame", ply).replaceAll("\\{SCORE}", prevhigh+""));
		}
		
		
		int particles = config.getInt("particle-count");
		if(
				!config.getString("new-block-particle").equalsIgnoreCase("none")
				&& particles > 0
				&& (VersionSupport.getMajorVersion() >= 26 || VersionSupport.getMinorVersion() >= 9)
		) {
			Location njl = nj.getTo();
			ply.spawnParticle(Particle.valueOf(config.getString("new-block-particle")), njl.getBlockX()+0.5, njl.getBlockY()+0.5, njl.getBlockZ()+0.5, particles, 0.005, 0.001, 0.005);
		}
		
		
		playSound("jump-sound", ply, nj.getFrom());
		
		plugin.rewards.checkRewards(this, score, area);
		
		PlayerJumpEvent je = new PlayerJumpEvent(this);
		Bukkit.getPluginManager().callEvent(je);
	}
	
	
	private void playSound(String configkey, Player ply) {
		playSound(configkey, ply, ply.getLocation());
	}
	private void playSound(String configkey, Player ply, Location loc) {
		String soundraw = plugin.getAConfig().getString(configkey);
		if(VersionSupport.getMajorVersion() == 1 && VersionSupport.getMinorVersion() <= 8 && soundraw.equalsIgnoreCase("ENTITY_CHICKEN_EGG")) {
			soundraw = "CHICKEN_EGG_POP";
		}
		if(soundraw == null) return;
		if(!soundraw.equalsIgnoreCase("none")) {
			Sound sound = null;
			try {
				sound = Sound.valueOf(soundraw);
			} catch(IllegalArgumentException ignored) { }
			if(sound != null) {
				if(VersionSupport.getMajorVersion() >= 26 || VersionSupport.getMinorVersion() >= 12) {
					ply.playSound(loc, sound, SoundCategory.MASTER, 1, 1);
				} else {
					ply.playSound(loc, sound, 1, 1);
				}
			} else {
				Bukkit.getLogger().warning("[ajParkour] Cannot find jump sound '"+soundraw+"'! Make sure it exists on the server version you are running!");
			}
		}
	}
	
	/**
	 * Add a command to execute after the parkour ends.
	 * @param cmd The command to execute in a string.
	 */
	public void addCommand(String cmd) {
		cmds.add(cmd);
	}
	/**
	 * Add a list of commands to execute after the parkour ends.
	 * @param cmds The commands to execute in a string.
	 */
	public void addCommands(List<String> cmds) {
		this.cmds.addAll(cmds);
	}
	
	/**
	 * Gets the highest (y-level) block
	 * @return The PkJump with the highest y-level
	 */
	public PkJump getHighestBlock() {
		PkJump highest = null;
		int y = Integer.MIN_VALUE;
		for(PkJump j : jumps) {
			int jy = j.getTo().getBlockY();
			if(jy > y) {
				y = jy;
				highest = j;
			}
		}
		return highest;
	}
	
	
	/**
	 * Check if the player made the jump
	 * @return A boolean telling if they made the jump or not.
	 */
	public boolean checkMadeIt() {
		if(!ready || jumps.size() < 2) return false;
		double x = ply.getLocation().getX();
		double z = ply.getLocation().getZ();
		
		Location goal = jumps.get(1).getTo();
		double xg = goal.getX()+0.5;
		double zg = goal.getZ()+0.5;
		double xdist = Math.abs(x - xg);
		double ydist = Math.abs(z - zg);
		//ply.sendMessage("x: "+xdist+"\ny: "+ydist);
		if(xdist < 0.8 && ydist < 0.8) {
			madeIt();
			return true;
		}
		return false;
	}
	
	/**
	 * Check if the player fell. If they did, end the parkour
	 */
	public void checkFall() {
		if(!ready || jumps.isEmpty()) return;
		int below = 1;
		Location plyloc = ply.getLocation();
		int my = jumps.get(0).getTo().getBlockY();
		if(
				plyloc.getBlockY() < my-below ||
				ply.isFlying() ||
				plyloc.getBlockY() > getHighestBlock().getTo().getBlockY()+3
			) {
			end();
		}
	}
	
	/**
	 * Get the {@link org.bukkit.entity.Player Player} this instance represents.
	 * @return The {@link org.bukkit.entity.Player Player} this instance represents.
	 */
	public Player getPlayer() {
		return ply;
	}
	/**
	 * Gets the player's current score
	 * @return The score
	 */
	public int getScore() {
		return score;
	}
	
	/**
	 * The material of the blocks. This is selected when the parkour starts.
	 * @return A {@link org.bukkit.Material Material}
	 */
	public String getBlock() {
		return this.block;
	}
	
	
	
	/**
	 * Used to get all the jumps for this player
	 * @return A {@link java.util.List list} of all {@link us.ajg0702.parkour.game.PkJump PkJump}s
	 */
	public List<PkJump> getJumps() {
		return jumps;
	}
	

	
	
	/**
	 * End the parkour with no reason
	 */
	public void end() {
		end("");
	}
	/**
	 * End the parkour with a reason
	 * @param reason The reason to end the parkour
	 */
	public void end(String reason) {
		// Make end() run exactly once, even if several events (move, quit, afk, teleport) race to end.
		synchronized(this) {
			if(ended) return;
			ended = true;
			active = false;
		}

		// Cleanup first so nothing leaks even if something below throws.
		if(afktask != null) afktask.cancel();
		if(clearPotsTask != null) clearPotsTask.cancel();
		if(fastAfkCheckTask != null) fastAfkCheckTask.cancel();
		HandlerList.unregisterAll(this);
		man.releaseStart(ply.getUniqueId());
		if(!man.pluginDisabling) {
			man.checkActive();
		}

		// Remove blocks on the region thread that owns the parkour area.
		FoliaScheduler.runAtLocation(plugin, area.getPos1(), () -> {
			for(PkJump j : jumps) {
				j.remove();
			}
		});
		
		if(!reason.isEmpty()) {
			ply.sendMessage(msgs.get("fall.force.base")+reason);
		}
		ply.sendMessage(msgs.get("fall.normal").replaceAll("\\{SCORE}", score+""));

		Runnable hsTask = () -> {
			int prevscore = scores.getHighScore(ply.getUniqueId(), area.getName());
			if(prevscore < score) {
				int time = (int) (System.currentTimeMillis() - started)/1000;
				scores.setScore(ply.getUniqueId(), score, time, area.getName());
			}
			String scoreArea = plugin.getAConfig().getBoolean("begin-score-per-area") ? area.getName() : null;
			int messageScore = scores.getHighScore(ply.getUniqueId(), scoreArea);
			if(messageScore < score) {
				FoliaScheduler.runForEntity(plugin, ply, () ->
						ply.sendMessage(msgs.get("beatrecord", ply).replaceAll("\\{SCORE}", prevscore+"")));
			}
		};
		if(man.pluginDisabling) {
			hsTask.run();
		} else {
			FoliaScheduler.runAsync(plugin, hsTask);
		}
		
		
		
		ply.setFallDistance(Integer.MIN_VALUE);
		
		if(area.getFallPos() != null) {
			teleporting = true;
			try {
				FoliaScheduler.teleport(ply, area.getFallPos());
			} catch(RuntimeException e) {
				Bukkit.getLogger().warning("[ajParkour] Could not teleport player to fall position: " + e.getMessage());
			}
		}
		
		
		if(config.getBoolean("parkour-inventory")) {
			ply.getInventory().clear();
			try {
				InvManager.restoreInventory(ply);
			} catch (IOException e) {
				ply.sendMessage("&cAn error occured while trying to restore your inventory!");
				Bukkit.getLogger().severe("[ajParkour] An error occured while trying to restore player's inventory:");
				e.printStackTrace();
			}
		}
		
		playSound("end-sound", ply);
		
		if(infiniteJump && ijenableAfter) {
			ij.getJumpManager().enable(ply);
		}
		
		if(cmds.size() > 0) {
			Rewards.staticExecuteCommands(cmds, getPlayer());
		}
		
		PlayerEndParkourEvent ee = new PlayerEndParkourEvent(ply, score);
		Bukkit.getPluginManager().callEvent(ee);
	}
	
	
	/**
	 * Get the area that this parkour is in.
	 * @return The {@link us.ajg0702.parkour.game.PkArea PkArea} the parkour is in.
	 */
	public PkArea getArea() {
		return area;
	}
	
	
	@EventHandler
	public void onMove(PlayerMoveEvent e) {
		if(!e.getPlayer().equals(ply)) return;
		lastmove = System.currentTimeMillis();
	}

}
