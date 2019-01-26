/*
 * Copyright 2011 frdfsnlght <frdfsnlght@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.frdfsnlght.transporter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import com.frdfsnlght.transporter.api.Gate;
import com.frdfsnlght.transporter.api.GateException;
import com.frdfsnlght.transporter.api.Reservation;
import com.frdfsnlght.transporter.api.ReservationException;
import com.frdfsnlght.transporter.api.event.EntityArriveEvent;
import com.frdfsnlght.transporter.api.event.EntityDepartEvent;

/**
 *
 * @author frdfsnlght <frdfsnlght@gmail.com>
 */
public final class ReservationImpl implements Reservation {

    private static final Map<Integer,Long> gateLocks = new HashMap<Integer,Long>();
    private static final Map<Integer,Countdown> countdowns = new HashMap<Integer,Countdown>();

    private static long nextId = 1;
    private static final Map<Long,ReservationImpl> reservations = new HashMap<Long,ReservationImpl>();

    public static ReservationImpl get(long id) {
        return reservations.get(id);
    }

    public static ReservationImpl get(String playerName) {
        for (ReservationImpl r : reservations.values())
            if (playerName.equals(r.player.getName())) return r;
        return null;
    }

    public static ReservationImpl get(Player player) {
        return get(player.getName());
    }

    private static boolean put(ReservationImpl r) {
        if (reservations.put(r.localId, r) == null) {
            Utils.debug("put reservation %s", r.localId);
            return true;
        }
        return false;
    }

    private static boolean remove(ReservationImpl r) {
        if (reservations.remove(r.localId) != null) {
            Utils.debug("removed reservation %s", r.localId);
            return true;
        }
        return false;
    }

    public static void removeGateLock(Entity entity) {
        if (entity == null) return;
        Long expiry = gateLocks.get(entity.getEntityId());
        if (expiry == null) return;
        if (expiry <= System.currentTimeMillis()) {
            gateLocks.remove(entity.getEntityId());
            Utils.debug("removed gate lock for entity %d", entity.getEntityId());
        }
    }

    public static boolean isGateLocked(Entity entity) {
        if (entity == null) return false;
        return gateLocks.containsKey(entity.getEntityId());
    }

    public static void addGateLock(Entity entity) {
        if (entity == null) return;
        gateLocks.put(entity.getEntityId(), System.currentTimeMillis() + Config.getGateLockExpiration());
        Utils.debug("added gate lock for entity %d", entity.getEntityId());
    }

    public static void removeCountdown(Entity entity) {
        if (entity == null) return;
        Countdown countdown = countdowns.get(entity.getEntityId());
        if (countdown == null) return;
        countdowns.remove(entity.getEntityId());
        countdown.cancel();
        Utils.debug("removed countdown for entity %d", entity.getEntityId());
    }

    public static void removeCountdown(Countdown countdown) {
        if (countdown == null) return;
        for (Iterator<Integer> i = countdowns.keySet().iterator(); i.hasNext(); ) {
            int id = i.next();
            if (countdowns.get(id) == countdown) {
                i.remove();
                Utils.debug("removed countdown for entity %d", countdown.getPlayer().getEntityId());
            }
        }
    }

    public static void removeCountdowns(GateImpl gate) {
        for (Iterator<Integer> i = countdowns.keySet().iterator(); i.hasNext(); ) {
            int id = i.next();
            Countdown countdown = countdowns.get(id);
            if (countdown.getGate() == gate) {
                i.remove();
                Utils.debug("removed countdown for entity %d", countdown.getPlayer().getEntityId());
            }
        }
    }

    public static void addCountdown(Countdown countdown) {
        if (countdown == null) return;
        countdowns.put(countdown.getPlayer().getEntityId(), countdown);
        Utils.debug("added countdown for entity %d", countdown.getPlayer().getEntityId());
    }

    public static boolean hasCountdown(Entity entity) {
        if (entity == null) return false;
        return countdowns.containsKey(entity.getEntityId());
    }

    private long localId = nextId++;
    private boolean departing = true;

    private EntityType entityType = null;
    private Entity entity = null;
    private Player player = null;

    private ItemStack[] inventory = null;
    private double health = 0;
    private int remainingAir = 0;
    private int fireTicks = 0;
    private int foodLevel = 0;
    private float exhaustion = 0;
    private float saturation = 0;
    private String gameMode = null;
    private int heldItemSlot = 0;
    private ItemStack[] armor = null;
    private int level = 0;
    private float xp = 0;
    private PotionEffect[] potionEffects = null;

    private Location fromLocation = null;
    private Vector fromVelocity = null;
    private BlockFace fromDirection = null;
    private GateImpl fromGate = null;
    private World fromWorld = null;

    private Location toLocation = null;
    private Vector toVelocity = null;
    private BlockFace toDirection = null;
    private GateImpl toGate = null;
    private World toWorld = null;

    private boolean createdEntity = false;

    // player stepping into gate
    public ReservationImpl(Player player, GateImpl fromGate) throws ReservationException {
        addGateLock(player);
        extractPlayer(player);
        extractFromGate(fromGate);
    }

    // vehicle moving into gate
    public ReservationImpl(Vehicle vehicle, GateImpl fromGate) throws ReservationException {
        addGateLock(vehicle);
        extractVehicle(vehicle);
        extractFromGate(fromGate);
    }

    // player direct to location on this server
    public ReservationImpl(Player player, Location location) throws ReservationException {
        addGateLock(player);
        extractPlayer(player);
        toLocation = location;
    }

    // player direct to remote server, default world, spawn location
    public ReservationImpl(Player player) throws ReservationException {
        addGateLock(player);
        extractPlayer(player);
    }

    // player direct to remote server, specified world, spawn location
    public ReservationImpl(Player player, String worldName) throws ReservationException {
        addGateLock(player);
        extractPlayer(player);
    }

    // player direct to remote server, specified world, specified location
    public ReservationImpl(Player player, String worldName, double x, double y, double z) throws ReservationException {
        addGateLock(player);
        extractPlayer(player);
        toLocation = new Location(null, x, y, z);
    }

    /* Reservation interface */

    public Entity getEntity() {
        return entity;
    }

    public Gate getDepartureGate() {
        return fromGate;
    }

    public Gate getArrivalGate() {
        return toGate;
    }

    /* End Reservation interface */

    private void extractPlayer(Player player) {
        entityType = EntityType.PLAYER;
        entity = player;
        this.player = player;
        health = player.getHealth();
        remainingAir = player.getRemainingAir();
        fireTicks = player.getFireTicks();
        foodLevel = player.getFoodLevel();
        exhaustion = player.getExhaustion();
        saturation = player.getSaturation();
        gameMode = player.getGameMode().toString();
        PlayerInventory inv = player.getInventory();
        inventory = Arrays.copyOf(inv.getContents(), inv.getSize());
        heldItemSlot = inv.getHeldItemSlot();
        armor = inv.getArmorContents();
        level = player.getLevel();
        xp = player.getExp();
        potionEffects = player.getActivePotionEffects().toArray(new PotionEffect[] {});
        fromLocation = player.getLocation();
        fromVelocity = player.getVelocity();
    }

    private void extractVehicle(Vehicle vehicle) {
        for (Entity passenger : vehicle.getPassengers())
	        if (passenger instanceof Player)
	            extractPlayer((Player)passenger);

        if ((vehicle instanceof Minecart) || (vehicle instanceof Boat))
            entityType = vehicle.getType();
        else
            throw new IllegalArgumentException("can't create state for " + vehicle.getClass().getName());

        if (vehicle instanceof InventoryHolder) {
            org.bukkit.inventory.Inventory inv = ((InventoryHolder)vehicle).getInventory();
            inventory = Arrays.copyOf(inv.getContents(), inv.getSize());
        }

        entity = vehicle;
        fromLocation = vehicle.getLocation();
        fromVelocity = vehicle.getVelocity();
        Utils.debug("vehicle location: %s", fromLocation);
        Utils.debug("vehicle velocity: %s", fromVelocity);
    }

    private void extractFromGate(GateImpl fromGate) throws ReservationException {
        this.fromGate = fromGate;
        fromDirection = fromGate.getDirection();
        fromWorld = fromGate.getWorld();

        try {
            toGate = fromGate.getDestinationGate();
        } catch (GateException ge) {
            throw new ReservationException(ge.getMessage());
        }

        toWorld = toGate.getWorld();
    }

    public boolean isDeparting() {
        return departing;
    }

    public boolean isArriving() {
        return ! departing;
    }

    // called to handle departure on the sending side
    public void depart() throws ReservationException {
        put(this);
        try {
            addGateLock(entity);
            if (entity != player)
                addGateLock(player);

            checkDepartureGate();
            checkArrivalGate();

            EntityDepartEvent event = new EntityDepartEvent(this);
            Bukkit.getPluginManager().callEvent(event);

            arrive();
            completeDepartureGate();
        } catch (ReservationException e) {
            remove(this);
            throw e;
        }
    }

    // called on the receiving side to handle arrival
    public void arrive() throws ReservationException {
        remove(this);

        if (toGate != null)
            toGate.attach(fromGate);

        prepareDestination();
        prepareTraveler();
        addGateLock(entity);
        if (entity != player)
            addGateLock(player);
        if (toLocation != null) {
            // NEW: Bukkit no longer teleports vehicles with passengers
            if ((entity == player) || (player == null)) {
                if (! entity.teleport(toLocation)) {
                    rollbackTraveler();
                    throw new ReservationException("teleport %s to %s failed", getTraveler(), getDestination());
                }
            }
        }
        commitTraveler();

        Utils.debug("%s arrived at %s", getTraveler(), getDestination());

        completeArrivalGate();

        arrived();
    }

    // called on the sending side to confirm reception of the valid reservation on the receiving side
    public void approved() {
        Utils.debug("reservation to send %s to %s was approved", getTraveler(), getDestination());

        if (player != null) {
            completeDepartureGate();
        }
        if ((entity != null) && (entity != player))
            entity.remove();

        EntityDepartEvent event = new EntityDepartEvent(this);
        Bukkit.getPluginManager().callEvent(event);

    }

    // called on the sending side to indicate a reservation was denied by the receiving side
    public void denied(final String reason) {
        remove(this);
        if (player == null)
            Utils.warning("reservation to send %s to %s was denied: %s", getTraveler(), getDestination(), reason);
        else {
            Context ctx = new Context(player);
            ctx.warn(reason);
        }
    }

    // called on the sending side to indicate an expected arrival arrived on the receiving side
    public void arrived() {
        remove(this);
        Utils.debug("reservation to send %s to %s was completed", getTraveler(), getDestination());

        EntityArriveEvent event = new EntityArriveEvent(this);
        Bukkit.getPluginManager().callEvent(event);

    }

    // called on the sending side to indicate an expected arrival never happened on the receiving side
    public void timeout() {
        remove(this);
        Utils.warning("reservation to send %s to %s timed out", getTraveler(), getDestination());
    }




    // called after arrival to get the destination on the local server where the entity arrived
    public Location getToLocation() {
        return toLocation;
    }


    private void checkDepartureGate() throws ReservationException {
        if (fromGate == null) return;

        if (player != null) {
            // player permission
            try {
                Permissions.require(player, "trp.gate.use." + fromGate.getFullName());
            } catch (PermissionsException e) {
                throw new ReservationException(e.getMessage());
            }
        }
    }

    private void checkArrivalGate() throws ReservationException {
        if (toGate == null) return;

        if (player != null) {
            // player permission
            try {
                Permissions.require(player, "trp.gate.use." + toGate.getFullName());
            } catch (PermissionsException e) {
                throw new ReservationException(e.getMessage());
            }
        }
    }

    private void completeDepartureGate() {
        if (fromGate == null) return;

        // Handle lightning strike...

        fromGate.onSend(entity);

    }

    private void completeArrivalGate() {
        if (toGate == null) return;

        toGate.onReceive(entity);

        if (player != null) {

            Context ctx = new Context(player);

            if (toGate.getTeleportFormat() != null) {
                String format = toGate.getTeleportFormat();
                format = format.replace("%player%", player.getDisplayName());
                format = format.replace("%toGateCtx%", toGate.getName(ctx));
                format = format.replace("%toGate%", toGate.getName());
                format = format.replace("%toWorld%", toGate.getWorld().getName());
                format = format.replace("%fromGateCtx%", (fromGate == null) ? "" : fromGate.getName(ctx));
                format = format.replace("%fromGate%", (fromGate == null) ? "" : fromGate.getName());
                format = format.replace("%fromWorld%", fromWorld.getName());
                if (! format.isEmpty())
                    ctx.send(format);
            }

        } else {
            Utils.debug("%s arrived at '%s'", getTraveler(), toGate.getFullName());
        }
    }

    private void prepareDestination() {
        if (toGate != null) {
            toLocation = toGate.getSpawnLocation(fromLocation, fromDirection);
            toVelocity = fromVelocity.clone();
            Utils.rotate(toVelocity, fromDirection, toGate.getDirection());
            Utils.debug("fromLocation: %s", fromLocation);
            Utils.debug("fromVelocity: %s", fromVelocity);
            Utils.debug("toLocation: %s", toLocation);
            Utils.debug("toVelocity: %s", toVelocity);
        } else {
            if (toLocation == null) {
                if (toWorld == null)
                    toWorld = Bukkit.getWorlds().get(0);
                toLocation = toWorld.getSpawnLocation();
            } else if (toLocation.getWorld() == null)
                toLocation.setWorld(Bukkit.getWorlds().get(0));
            toLocation.setYaw(fromLocation.getYaw());
            toLocation.setPitch(fromLocation.getPitch());
            toVelocity = fromVelocity.clone();
        }
        if (toLocation != null) {
            Utils.prepareChunk(toLocation);

            // tweak velocity so we don't get buried in a block
            Location nextLocation = toLocation.clone().add(toVelocity.getX(), toVelocity.getY(), toVelocity.getZ());
            Utils.prepareChunk(nextLocation);
            switch (nextLocation.getBlock().getType()) {
                // there are probably others
                case AIR:
                case WATER:
                case STATIONARY_WATER:
                case LAVA:
                case STATIONARY_LAVA:
                case WEB:
                case TORCH:
                case REDSTONE_TORCH_OFF:
                case REDSTONE_TORCH_ON:
                case SIGN_POST:
                case RAILS:
                    break;
                default:
                    // should we try to zero just each ordinate and test again?
                    Utils.debug("zeroing velocity to avoid block");
                    toVelocity.zero();
                    break;
            }
        }

        Utils.debug("destination location: %s", toLocation);
        Utils.debug("destination velocity: %s", toVelocity);
    }

    private void prepareTraveler() throws ReservationException {
        Utils.debug("prepareTraveler %s", getTraveler());
        if ((player == null)) {
            throw new ReservationException("player not found");
        }

        World theWorld;
        Location theLocation;
        if (toLocation == null) {
            theWorld = Bukkit.getWorlds().get(0);
            theLocation = theWorld.getSpawnLocation();
        } else {
            theWorld = toLocation.getWorld();
            theLocation = toLocation;
        }

        if (entity == null) {
            switch (entityType) {
                case PLAYER:
                    entity = player;
                    break;
                case MINECART:
                    entity = theWorld.spawn(theLocation, org.bukkit.entity.minecart.RideableMinecart.class);
                    createdEntity = true;
                    if (player != null)
                        ((RideableMinecart)entity).addPassenger(player);
                    break;
                case MINECART_CHEST:
                    entity = theWorld.spawn(theLocation, org.bukkit.entity.minecart.StorageMinecart.class);
                    createdEntity = true;
                    break;
                case MINECART_FURNACE:
                    entity = theWorld.spawn(theLocation, org.bukkit.entity.minecart.PoweredMinecart.class);
                    createdEntity = true;
                    break;
                case MINECART_HOPPER:
                    entity = theWorld.spawn(theLocation, org.bukkit.entity.minecart.HopperMinecart.class);
                    createdEntity = true;
                    break;
                case MINECART_MOB_SPAWNER:
                    entity = theWorld.spawn(theLocation, org.bukkit.entity.minecart.SpawnerMinecart.class);
                    createdEntity = true;
                    break;
                case MINECART_TNT:
                    entity = theWorld.spawn(theLocation, org.bukkit.entity.minecart.ExplosiveMinecart.class);
                    createdEntity = true;
                    break;
                case BOAT:
                    entity = theWorld.spawn(theLocation, Boat.class);
                    createdEntity = true;
                    break;
                default:
                    throw new ReservationException("unknown entity type '%s'", entityType);
            }
        } else if ((entity != player) && (player != null)) {
            Utils.debug("spoofing vehicle/passenger teleportation");
            switch (entityType) {
                case MINECART:
                    entity.remove();
                    entity = theWorld.spawn(theLocation, org.bukkit.entity.minecart.RideableMinecart.class);
                    ((RideableMinecart)entity).addPassenger(player);
                    break;
                case BOAT:
                    entity.remove();
                    entity = theWorld.spawn(theLocation, Boat.class);
                    ((Boat)entity).addPassenger(player);
                    break;
                default: break;
            }
        }
        if (player != null) {
            if (health < 0) health = 0;
            if (remainingAir < 0) remainingAir = 0;
            if (foodLevel < 0) foodLevel = 0;
            if (exhaustion < 0) exhaustion = 0;
            if (saturation < 0) saturation = 0;
            if (level < 0) level = 0;
            if (xp < 0) xp = 0;

            if (toGate == null) {
                player.setHealth(health);
                player.setRemainingAir(remainingAir);
                player.setFoodLevel(foodLevel);
                player.setExhaustion(exhaustion);
                player.setSaturation(saturation);
                player.setFireTicks(fireTicks);
            }
            if (player == entity)
                player.setVelocity(toVelocity);
            if (inventory != null) {
                PlayerInventory inv = player.getInventory();
                for (int slot = 0; slot < inventory.length; slot++) {
                    if (inventory[slot] == null)
                        inv.setItem(slot, new ItemStack(Material.AIR));
                    else
                        inv.setItem(slot, inventory[slot]);
                }
                // PENDING: This doesn't work as expected. it replaces whatever's
                // in slot 0 with whatever's in the held slot. There doesn't appear to
                // be a way to change just the slot of the held item
                //inv.setItemInHand(inv.getItem(heldItemSlot));
            }
            if (armor != null) {
                PlayerInventory inv = player.getInventory();
                inv.setArmorContents(armor);
            }
            if (potionEffects != null) {
                for (PotionEffectType pet : PotionEffectType.values()) {
                    if (pet == null) continue;
                    if (player.hasPotionEffect(pet))
                        player.removePotionEffect(pet);
                }
                for (PotionEffect effect : potionEffects) {
                    if (effect == null) continue;
                    player.addPotionEffect(effect);
                }
            }
        }

        if (player != entity)
            entity.setVelocity(toVelocity);
    }

    private void rollbackTraveler() {
        if (createdEntity)
            entity.remove();
    }

    private void commitTraveler() {
        // TODO: something?
    }

    public String getTraveler() {
        if (entityType == EntityType.PLAYER)
            return String.format("player '%s'", player.getName());
        return String.format("player '%s' as a passenger on a %s", player.getName(), entityType);
    }

    public String getDestination() {
        if (toGate != null)
            return "'" + toGate.getName() + "'";
        String dst;
        if (toWorld != null)
            dst = String.format("world '%s'", toWorld.getName());
        else
            dst = "unknown";
        if (toLocation != null)
            dst += String.format(" @ %s,%s,%s", toLocation.getBlockX(), toLocation.getBlockY(), toLocation.getBlockZ());
        return dst;
    }

}
