package com.sainttx.auctions.structure;

import com.sainttx.auctions.AuctionPlugin;
import com.sainttx.auctions.api.Auction;
import com.sainttx.auctions.api.AuctionManager;
import com.sainttx.auctions.api.AuctionType;
import com.sainttx.auctions.api.AuctionsAPI;
import com.sainttx.auctions.api.messages.MessageHandler;
import com.sainttx.auctions.api.module.AuctionModule;
import com.sainttx.auctions.api.reward.Reward;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;

/**
 * An auction implementation
 */
public abstract class AbstractAuction implements Auction {

    // Instance
    protected AuctionPlugin plugin;
    protected AuctionType type;
    protected Collection<AuctionModule> modules = new HashSet<AuctionModule>();

    // Auction owner information
    protected UUID ownerUUID;
    protected String ownerName;

    // Top bidder information
    protected UUID topBidderUUID;
    protected String topBidderName;
    protected double winningBid;

    // Auction information
    protected Reward reward;
    protected double bidIncrement;
    protected double autowin = -1;
    protected int timeLeft;
    protected BukkitTask timerTask;

    /**
     * Creates an Auction
     *
     * @param plugin the auction plugin instance
     * @param type   the specified auction type
     */
    AbstractAuction(AuctionPlugin plugin, AuctionType type) {
        this.plugin = plugin;
        this.type = type;
    }

    @Override
    public UUID getOwner() {
        return ownerUUID;
    }

    @Override
    public String getOwnerName() {
        return ownerName;
    }

    @Override
    public UUID getTopBidder() {
        return topBidderUUID;
    }

    @Override
    public String getTopBidderName() {
        return topBidderName;
    }

    @Override
    public Reward getReward() {
        return reward;
    }

    @Override
    public AuctionType getType() {
        return type;
    }

    @Override
    public double getTopBid() {
        return winningBid;
    }

    @Override
    public double getAutowin() {
        return autowin;
    }

    @Override
    public void placeBid(Player player, double bid) {
        if (player == null) {
            throw new IllegalArgumentException("player cannot be null");
        }

        MessageHandler handler = AuctionsAPI.getMessageHandler();

        if (bid < getTopBid() + getBidIncrement()) {
            handler.sendMessage(player, plugin.getMessage("messages.error.bidTooLow")); // the bid wasnt enough
        } else if (plugin.getEconomy().getBalance(player) < bid) {
            handler.sendMessage(player, plugin.getMessage("messages.error.insufficientBalance")); // insufficient funds
        } else if (player.getUniqueId().equals(getTopBidder())) {
            handler.sendMessage(player, plugin.getMessage("messages.error.alreadyTopBidder")); // already top bidder
        } else {
            if (getTopBidder() != null) { // give the old winner their money back
                OfflinePlayer oldPlayer = Bukkit.getOfflinePlayer(getTopBidder());
                plugin.getEconomy().depositPlayer(oldPlayer, getTopBid());
            }

            this.winningBid = bid;
            this.topBidderName = player.getName();
            this.topBidderUUID = player.getUniqueId();
            plugin.getEconomy().withdrawPlayer(player, bid);
            broadcastBid();

            // Trigger our modules
            for (AuctionModule module : modules) {
                if (module.canTrigger()) {
                    module.trigger();
                }
            }
        }
    }

    /**
     * Broadcasts the most recent bid
     */
    public abstract void broadcastBid();

    @Override
    public int getTimeLeft() {
        return timeLeft;
    }

    @Override
    public void setTimeLeft(int time) {
        this.timeLeft = time;
    }

    @Override
    public void start() {
        this.timerTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new AuctionTimer(), 20L, 20L);
        startMessages();
    }

    /**
     * Dispatches messages for the start of the auction
     */
    protected void startMessages() {
        AuctionManager manager = AuctionsAPI.getAuctionManager();
        MessageHandler handler = manager.getMessageHandler();

        handler.broadcast(plugin.getMessage("messages.auctionFormattable.start"), this, false);
        handler.broadcast(plugin.getMessage("messages.auctionFormattable.price"), this, false);
        handler.broadcast(plugin.getMessage("messages.auctionFormattable.increment"), this, false);
    }

    @Override
    public void impound() {
        timerTask.cancel();
        timerTask = null;
        runNextAuctionTimer();

        // Return the top bidders money
        returnMoneyToAll();

        // Set current auction to null
        AuctionsAPI.getAuctionManager().setCurrentAuction(null);
    }

    @Override
    public void cancel() {
        Player owner = Bukkit.getPlayer(ownerUUID);
        timerTask.cancel();
        timerTask = null;

        // Run the next auction timer
        if (plugin.isEnabled()) {
            runNextAuctionTimer(); // This handles setting the canStartNewAuction status
        }

        // Return the item to the owner
        if (getOwner() == null) {
            plugin.getLogger().info("Saving items of offline player " + getOwnerName() + " (uuid: " + getOwner() + ")");
            plugin.saveOfflinePlayer(getOwner(), getReward());
        } else {
            getReward().giveItem(owner);
        }

        // Return the top bidders money
        returnMoneyToAll();

        // Broadcast
        MessageHandler handler = AuctionsAPI.getAuctionManager().getMessageHandler();
        handler.broadcast(plugin.getMessage("messages.auctionFormattable.cancelled"), this, false);

        // Set current auction to null
        AuctionsAPI.getAuctionManager().setCurrentAuction(null);
    }

    /*
     * Returns all bidders money
     */
    protected void returnMoneyToAll() {
        if (getTopBidder() != null) {
            OfflinePlayer topBidder = Bukkit.getOfflinePlayer(getTopBidder());
            plugin.getEconomy().depositPlayer(topBidder, getTopBid());
        }
    }

    @Override
    public void end(boolean broadcast) {
        AuctionManager manager = AuctionsAPI.getAuctionManager();
        MessageHandler handler = manager.getMessageHandler();
        Player owner = Bukkit.getPlayer(getOwner());
        timerTask.cancel();
        timerTask = null;

        // Run the next auction timer
        if (plugin.isEnabled()) {
            runNextAuctionTimer();
        }

        if (getTopBidder() != null) {
            Player winner = Bukkit.getPlayer(getTopBidder());

            // Give the winner their items
            if (winner == null) {
                plugin.getLogger().info("Saving items of offline player " + getTopBidderName() + " (uuid: " + getTopBidder() + ")");
                plugin.saveOfflinePlayer(getTopBidder(), getReward());
            } else {
                getReward().giveItem(winner);
                handler.sendMessage(winner, plugin.getMessage("messages.auctionFormattable.winner"), this);
            }

            if (getTopBid() > 0) {
                double winnings = getTopBid() - getTaxAmount();
                plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(getOwner()), winnings);

                if (owner != null) {
                    if (getTax() > 0) {
                        handler.sendMessage(owner, plugin.getMessage("messages.auctionFormattable.endTax"), this);
                    }
                    handler.sendMessage(owner, plugin.getMessage("messages.auctionFormattable.endNotifyOwner"), this);
                }
            }

            if (broadcast) {
                handler.broadcast(plugin.getMessage("messages.auctionFormattable.end"), this, false);
            }
        } else {
            if (owner != null) {
                getReward().giveItem(owner);
                handler.sendMessage(owner, plugin.getMessage("messages.ownerItemReturn"));
            } else {
                plugin.getLogger().info("Saving items of offline player " + getOwnerName() + " (uuid: " + getOwner() + ")");
                plugin.saveOfflinePlayer(getOwner(), getReward());
            }

            if (broadcast) {
                handler.broadcast(plugin.getMessage("messages.auctionFormattable.endNoBid"), this, false);
            }
        }

        // Set current auction to null
        AuctionsAPI.getAuctionManager().setCurrentAuction(null);
    }

    /*
     * Schedules a new auction after a 'auctionSettings.delayBetween' second delay
     */
    private void runNextAuctionTimer() {
        // Delay before a new auction can be made... Prevents auction scamming
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, new Runnable() {
                @Override
                public void run() {
                    AuctionsAPI.getAuctionManager().setCanStartNewAuction(true);

                    // Start the next auction in the queue
                    if (AuctionsAPI.getAuctionManager().getCurrentAuction() == null) {
                        AuctionsAPI.getAuctionManager().startNextAuction();
                    }
                }
            }, plugin.getConfig().getLong("auctionSettings.delayBetween", 5L) * 20L);
        }
    }

    @Override
    public double getBidIncrement() {
        return bidIncrement;
    }

    @Override
    public double getTax() {
        return plugin.getConfig().getInt("auctionSettings.taxPercent", 0);
    }

    @Override
    public double getTaxAmount() {
        return (getTopBid() * getTax()) / 100;
    }

    @Override
    public Collection<AuctionModule> getModules() {
        return new HashSet<AuctionModule>(modules);
    }

    @Override
    public void addModule(AuctionModule module) {
        if (module == null) {
            throw new IllegalArgumentException("module cannot be null");
        }

        this.modules.add(module);
    }

    @Override
    public boolean removeModule(AuctionModule module) {
        return this.modules.remove(module);
    }

    /**
     * An implementation of an auction timer
     */
    public class AuctionTimer implements Auction.Timer {

        @Override
        public void run() {
            timeLeft--;

            if (timeLeft <= 0) {
                end(true);
            } else if (plugin.isBroadcastTime(timeLeft)) {
                MessageHandler handler = AuctionsAPI.getAuctionManager().getMessageHandler();
                handler.broadcast(plugin.getMessage("messages.auctionFormattable.timer"), AbstractAuction.this,
                        false);
            }
        }
    }

    /**
     * An implementation of an Auction builder for auctions
     */
    public static abstract class AbstractAuctionBuilder implements Builder {

        protected AuctionPlugin plugin;
        protected double increment = -1;
        protected int time = -1;
        protected Reward reward;
        protected double bid = -1;
        protected double autowin = -1;
        protected UUID ownerId;
        protected String ownerName;

        public AbstractAuctionBuilder(AuctionPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public Builder bidIncrement(double increment) {
            this.increment = increment;
            return this;
        }

        @Override
        public Builder owner(Player owner) {
            this.ownerId = owner.getUniqueId();
            this.ownerName = owner.getName();
            return this;
        }

        @Override
        public Builder time(int time) {
            this.time = time;
            return this;
        }

        @Override
        public Builder reward(Reward reward) {
            this.reward = reward;
            return this;
        }

        @Override
        public Builder topBid(double bid) {
            this.bid = bid;
            return this;
        }

        @Override
        public Builder autowin(double autowin) {
            this.autowin = autowin;
            return this;
        }

        /**
         * Initializes any default values that haven't been set
         */
        protected void defaults() {
            if (reward == null) {
                throw new IllegalStateException("reward cannot be null");
            } else if (bid == -1) {
                throw new IllegalStateException("bid hasn't been set");
            }
            if (increment == -1) {
                increment = plugin.getConfig().getInt("auctionSettings.defaultBidIncrement", 50);
            }
            if (time == -1) {
                time = plugin.getConfig().getInt("auctionSettings.startTime", 30);
            }
        }
    }
}