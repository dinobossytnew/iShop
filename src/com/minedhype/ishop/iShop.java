package com.minedhype.ishop;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import com.minedhype.ishop.gui.GUIEvent;
import com.minedhype.ishop.MetricsLite;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.scheduler.BukkitTask;

public class iShop extends JavaPlugin {
	File configFile;
	public static FileConfiguration config;
	public static WorldGuardLoader wgLoader = null;
	private static BukkitTask expiredTaskId, saveTaskId, tickTaskId;
	private static Connection connection = null;
	private static Economy economy = null;
	private static String chainConnect;

	@Override
	public void onLoad() {
		Plugin wgCheck = Bukkit.getPluginManager().getPlugin("WorldGuard");
		if(wgCheck != null)
			wgLoader = new WorldGuardLoader();
	}

	@Override
	public void onEnable() {
		chainConnect = "jdbc:sqlite:"+getDataFolder().getAbsolutePath()+"/shops.db";
		this.setupEconomy();
		this.createConfig();
		if(config.getString("shopBlock") == null) {
			config.set("shopBlock", "minecraft:barrel");
			Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[iShop] " + Messages.NO_SHOP_BLOCK.toString());
		}
		if(config.getString("stockBlock") == null) {
			config.set("stockBlock", "minecraft:composter");
			Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[iShop] " + Messages.NO_STOCK_BLOCK.toString());
		}
		getServer().getPluginManager().registerEvents(new EventShop(), this);
		getServer().getPluginManager().registerEvents(new GUIEvent(), this);
		getCommand("ishop").setExecutor(new CommandShop());
		try {
			connection = DriverManager.getConnection(chainConnect);
			this.createTables();
			Shop.loadData();
		} catch(Exception e) { e.printStackTrace(); }
		expiredTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, Shop::expiredShops, 5, 20000);
		saveTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, Shop::saveData, 500, 6000);
		tickTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, Shop::tickShops, 100, 50);
		Bukkit.getScheduler().runTaskLaterAsynchronously(this, Shop::getPlayersShopList, 70);
		MetricsLite metrics = new MetricsLite(this, 9189);
		new UpdateChecker(this, 84555).getVersion(version -> {
			if(!this.getDescription().getVersion().equalsIgnoreCase(version))
				getServer().getConsoleSender().sendMessage(ChatColor.RED + "[iShop] There is a new update available! - https://www.spigotmc.org/resources/ishop.84555/");
		});
	}

	@Override
	public void onDisable() {
		tickTaskId.cancel();
		expiredTaskId.cancel();
		if(Bukkit.getScheduler().isCurrentlyRunning(saveTaskId.getTaskId()))
			while(Bukkit.getScheduler().isCurrentlyRunning(saveTaskId.getTaskId()))
				;
		else {
			saveTaskId.cancel();
			Shop.saveData();
		}
		if(connection != null) {
			try {
				connection.close();
			} catch(SQLException e) {
				e.printStackTrace();
			}
		}
	}

	private void createTables() {
		PreparedStatement[] stmts = new PreparedStatement[] {};
		try {
			stmts = new PreparedStatement[] {
					connection.prepareStatement("CREATE TABLE IF NOT EXISTS zooMercaTiendas(id INTEGER PRIMARY KEY autoincrement, location varchar(64), owner varchar(64));"),
					connection.prepareStatement("CREATE TABLE IF NOT EXISTS zooMercaTiendasFilas(itemIn text, itemOut text, idTienda INTEGER);"),
					connection.prepareStatement("CREATE TABLE IF NOT EXISTS zooMercaStocks(owner varchar(64), items JSON);")
			};
		} catch(Exception e) { e.printStackTrace(); }
		for(PreparedStatement stmt : stmts) {
			try {
				stmt.execute();
				stmt.close();
			} catch(Exception e) { e.printStackTrace(); }
		}
		List<PreparedStatement> stmtsPatches = new ArrayList<>();
		try {
			stmtsPatches.add(connection.prepareStatement("ALTER TABLE zooMercaTiendasFilas ADD COLUMN itemIn2 text NULL DEFAULT 'v: 2580\ntype: AIR\namount: 0' "));
			stmtsPatches.add(connection.prepareStatement("ALTER TABLE zooMercaTiendasFilas ADD COLUMN itemOut2 text NULL DEFAULT 'v: 2580\ntype: AIR\namount: 0' "));
			stmtsPatches.add(connection.prepareStatement("ALTER TABLE zooMercaTiendasFilas ADD COLUMN broadcast BOOLEAN DEFAULT 0"));
			stmtsPatches.add(connection.prepareStatement("ALTER TABLE zooMercaStocks ADD COLUMN pag INTEGER DEFAULT 0"));
			stmtsPatches.add(connection.prepareStatement("ALTER TABLE zooMercaTiendas ADD COLUMN admin BOOLEAN DEFAULT FALSE;"));
		} catch(Exception ignored) { }
		for(PreparedStatement stmtsPatch : stmtsPatches) {
			try {
				stmtsPatch.execute();
				stmtsPatch.close();
			} catch (Exception ignored) { }
		}
	}

	public static Connection getConnection() {
		checkConnection();
		return connection;
	}

	public static void checkConnection() {
		try {
			if(connection == null || connection.isClosed() || !connection.isValid(0))
				connection = DriverManager.getConnection(chainConnect);
		} catch(Exception e) { e.printStackTrace(); }
	}

	private void setupEconomy() {
		if(getServer().getPluginManager().getPlugin("Vault") == null)
			return;
		RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
		if(rsp == null)
			return;
		economy = rsp.getProvider();
	}

	public static Optional<Economy> getEconomy() {
		return Optional.ofNullable(economy);
	}

	public void createConfig() {
		this.configFile = new File(getDataFolder(), "config.yml");
		if(!this.configFile.exists()) {
			this.configFile.getParentFile().mkdirs();
			saveResource("config.yml", false);
		}
		config = new YamlConfiguration();
		try {
			config.load(this.configFile);
			String ver = config.getDouble("configVersion")+"";
			switch(ver) {
				case "1.0":
					config.set("adminShopDisabled", "&cAdmin shops have been disabled!");
					config.set("listAdminShop", "&6Listing all found admin shops:");
					config.set("noAdminShopsFound", "&cNo admin shops have been found!");
					config.set("noShopBlock", "&cshopBlock cannot be empty! Reverting to default minecraft:barrel");
					config.set("noStockBlock", "&cstockBlock cannot be empty! Reverting to default minecraft:composter");
					config.set("notPlayer", "&cOnly players in the game can use shop commands!");
				case "1.1":
					config.set("buyTitle", "PRICE TO BUY ITEMS [SLOT 1]");
					config.set("buyTitle2", "PRICE TO BUY ITEMS [SLOT 2]");
					config.set("sellTitle", "ITEMS FOR SALE [SLOT 1]");
					config.set("sellTitle2", "ITEMS FOR SALE [SLOT 2]");
					config.set("foundShops", "&6Found&a %shops &6shops(s) for player:&a %p");
					config.set("location", "&6Shop&a %id &6location XYZ: ");
					config.set("enableShopNotifications", true);
					config.set("enableOutOfStockMessages", true);
					config.set("mustOwnShopForStock", true);
				case "2.0":
					config.set("publicShopListCommand", true);
					config.set("shopListTitle", "Shops List");
				case "2.1":
				case "2.2":
					config.set("publicShopListShowsOwned", true);
					config.set("shopListDisabled", "&cShops list has been disabled!");
				case "2.3":
					config.set("enableStockAccessFromShopGUI", true);
				case "2.4":
					config.set("adminShopPublic", true);
					config.set("placeItemFrameSigns", true);
					config.set("protectShopBlocksFromExplosions", true);
					config.set("adminShop", "Admin Shop #%id");
					config.set("clickManage", "&eMANAGE");
					config.set("clickShop", "&eSHOP");
					config.set("normalShop", "%player%'s Shop #%id");
					config.set("shopNumber", "%player's shop #%id");
					config.set("adminShopNumber", "Admin shop #%id");
					config.set("noStockButton", "&cItem(s) out of stock!");
					config.set("stockIntegerError", "&cStock page must be an integer greater than 0!");
				case "2.5":
					config.set("multipleShopBlocks", false);
					List<String> defaultShopBlockList = Arrays.asList("minecraft:chest", "minecraft:crafting_table");
					config.set("shopBlockList", defaultShopBlockList);
					config.set("multipleStockBlocks", false);
					List<String> defaultStockBlockList = Arrays.asList("minecraft:ender_chest", "minecraft:jukebox");
					config.set("stockBlockList", defaultStockBlockList);
					config.set("enableShopSoldMessage", true);
					config.set("enableSoldNotificationOnJoin", true);
					config.set("onlyNotifySoldOnceUntilClear", true);
					config.set("soldNotificationsDelayTime", 5);
					config.set("autoClearSoldListOnLast", false);
					config.set("soldClear", "&6Sold shop items list has been&c cleared");
					config.set("soldCommandDisabled", "&cShop sold command has been disabled");
					config.set("soldHeader", "&6Sold items while offline or last server restart (Page %p):");
					config.set("soldIntegerError", "&cSold page must be an integer greater than 0!");
					config.set("soldJoinNotify", "&6Type &a/shop sold&6 to see sold items or use &a/shop sold clear");
					config.set("soldNothing", "&cNo shop trades found while offline or since server restart");
					config.set("soldPages", "&7Page %p");
					config.set("soldPagesNext", " &7>> &6Next");
					config.set("soldPagesPrevious", "&6Previous &7<< ");
				case "2.6":
					config.set("disableShopInWorld", false);
					List<String> disabledWorlds = Arrays.asList("world_nether", "world_the_end");
					config.set("disabledWorldList", disabledWorlds);
					config.set("disabledWorld", "&cCreating shops in the world you are in has been disabled!");
					config.set("configVersion", 2.7);
					config.save(configFile);
				case "2.7":
					break;
			}
		} catch(IOException | InvalidConfigurationException e) { e.printStackTrace(); }
	}
	public static iShop getPlugin() {
		return iShop.getPlugin(iShop.class);
	}
}
