package dev.platt;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.inventory.meta.trim.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Paper 1.21.11. Single-threaded gameplay, scheduled timers, persist charge/cooldowns/trust. */
@SuppressWarnings("deprecation")
public final class PlattPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final List<String> TYPES = List.of("lapis","iron","redstone","copper","gold","emerald","diamond","netherite","quartz","amethyst","resin","breeze","echo");
    private static final Map<Material,String> INGREDIENTS = Map.ofEntries(
        Map.entry(Material.LAPIS_LAZULI,"lapis"), Map.entry(Material.IRON_INGOT,"iron"),
        Map.entry(Material.REDSTONE,"redstone"), Map.entry(Material.COPPER_INGOT,"copper"),
        Map.entry(Material.GOLD_INGOT,"gold"), Map.entry(Material.EMERALD,"emerald"),
        Map.entry(Material.DIAMOND,"diamond"), Map.entry(Material.NETHERITE_INGOT,"netherite"),
        Map.entry(Material.QUARTZ,"quartz"), Map.entry(Material.AMETHYST_SHARD,"amethyst"),
        Map.entry(Material.RESIN_BRICK,"resin"), Map.entry(Material.BREEZE_ROD,"breeze"),
        Map.entry(Material.ECHO_SHARD,"echo"));
    private final Map<UUID,Map<String,Charge>> states = new HashMap<>();
    private final Map<UUID,Set<UUID>> trusts = new HashMap<>();
    private final Map<UUID,String> selection = new HashMap<>();
    private final Map<UUID,Long> ironGuard = new HashMap<>(), copperConduct = new HashMap<>(), netheriteTank = new HashMap<>(), lapisSurge = new HashMap<>(), safeFall = new HashMap<>();
    private final Map<UUID,Long> diamondUntil = new HashMap<>(), breezeSmash = new HashMap<>(), echoMarkedUntil = new HashMap<>();
    private final Map<UUID,Integer> diamondHits = new HashMap<>();
    private final Map<UUID,Map<UUID,Integer>> echoMarks = new HashMap<>();
    private final Map<UUID,UUID> lastEnemy = new HashMap<>();
    private final Map<UUID,Long> lastActivation = new HashMap<>();
    private final Map<UUID,UUID> summonedOwners = new HashMap<>();
    private final Map<UUID,IronGolem> guardians = new HashMap<>();
    private final Map<UUID,Long> emeraldTradeDay = new HashMap<>();
    private NamespacedKey templateKey, patternKey;
    private File saveFile;

    private static final class Charge {
        int progress; long lastHit; boolean ready;
        final Map<Integer,Long> cooldown = new HashMap<>();
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        templateKey = new NamespacedKey(this,"platt_template");
        patternKey = new NamespacedKey("platt","platt");
        saveFile = new File(getDataFolder(),"players.yml");
        loadData();
        getServer().getPluginManager().registerEvents(this,this);
        for (String cmd : List.of("platt","trust","cooldown")) {
            PluginCommand c = Objects.requireNonNull(getCommand(cmd));
            c.setExecutor(this); c.setTabCompleter(this);
        }
        registerCrafting();
        if (Registry.TRIM_PATTERN.get(patternKey) == null ||
            Registry.TRIM_MATERIAL.get(new NamespacedKey("platt","breeze")) == null ||
            Registry.TRIM_MATERIAL.get(new NamespacedKey("platt","echo")) == null)
            getLogger().severe("PLATT DATAPACK MISSING. Install in world/datapacks then RESTART. Custom smithing is unavailable until the registries load.");
        getServer().getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            ironGuard.entrySet().removeIf(e -> e.getValue() <= now);
            copperConduct.entrySet().removeIf(e -> e.getValue() <= now);
            netheriteTank.entrySet().removeIf(e -> e.getValue() <= now);
            lapisSurge.entrySet().removeIf(e -> e.getValue() <= now);
            diamondUntil.entrySet().removeIf(e -> e.getValue() <= now);
            breezeSmash.entrySet().removeIf(e -> e.getValue() <= now);
            echoMarkedUntil.entrySet().removeIf(e -> e.getValue() <= now);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("platt.use") && active(p).equals("quartz")) scanQuartz(p, now);
            }
        },20L,20L);
        getLogger().info("Loaded Platt: 13 materials; all four armor slots.");
    }
    @Override public void onDisable() { saveData(); }
    private long ms(double seconds) { return (long)(seconds*1000); }
    private int i(String mat,String path,int def) { return getConfig().getInt("materials."+mat+"."+path,def); }
    private double d(String mat,String path,double def) { return getConfig().getDouble("materials."+mat+"."+path,def); }
    private long secs(String mat,int ability) { return ms(d(mat,"abilities."+ability+".cooldown",45)); }
    private Charge state(Player p,String mat) { return states.computeIfAbsent(p.getUniqueId(),k->new HashMap<>()).computeIfAbsent(mat,k->new Charge()); }
    private void msg(CommandSender s,String msg) { s.sendMessage(ChatColor.AQUA+"[Platt] "+ChatColor.WHITE+msg); }

    private void registerCrafting() {
        ShapedRecipe r = new ShapedRecipe(new NamespacedKey(this,"platt_template_recipe"),template());
        r.shape("IRI","RDR","IRI");
        r.setIngredient('I',Material.IRON_INGOT);
        r.setIngredient('R',Material.REDSTONE);
        r.setIngredient('D',Material.DIAMOND);
        Bukkit.addRecipe(r);
    }
    private ItemStack template() {
        ItemStack item = new ItemStack(Material.SMITHING_TEMPLATE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Platt Smithing Template",NamedTextColor.AQUA));
        meta.lore(List.of(Component.text("Use on any armor piece with one of 13 materials.",NamedTextColor.GRAY),
            Component.text("Unlocks a power after a combat charge.",NamedTextColor.DARK_AQUA)));
        meta.getPersistentDataContainer().set(templateKey,PersistentDataType.BYTE,(byte)1);
        meta.setItemModel(NamespacedKey.fromString(getConfig().getString("smithing.template-item-model","platt:platt_template")));
        item.setItemMeta(meta); return item;
    }
    private boolean isTemplate(ItemStack item) { return item!=null && item.hasItemMeta() &&
        item.getItemMeta().getPersistentDataContainer().has(templateKey,PersistentDataType.BYTE); }
    private boolean armor(ItemStack item) {
        if (item==null || item.getType().isAir() || !(item.getItemMeta() instanceof ArmorMeta)) return false;
        String n=item.getType().name();
        return n.endsWith("_HELMET")||n.endsWith("_CHESTPLATE")||n.endsWith("_LEGGINGS")||n.endsWith("_BOOTS");
    }
    private ItemStack smithResult(ItemStack templ,ItemStack equipment,ItemStack mineral) {
        if (!isTemplate(templ)||!armor(equipment)||mineral==null) return null;
        String type=INGREDIENTS.get(mineral.getType()); if(type==null) return null;
        TrimPattern pattern=Registry.TRIM_PATTERN.get(patternKey);
        TrimMaterial material=Registry.TRIM_MATERIAL.get(new NamespacedKey(type.equals("breeze")||type.equals("echo") ? "platt":"minecraft",type));
        if (pattern==null||material==null) return null;
        ItemStack copy=equipment.clone(); copy.setAmount(1);
        ArmorMeta meta=(ArmorMeta)copy.getItemMeta();
        if (meta.hasTrim() && !getConfig().getBoolean("smithing.allow-retrim",true)) return null;
        meta.setTrim(new ArmorTrim(material,pattern));
        copy.setItemMeta(meta); return copy;
    }
    @EventHandler public void prepare(PrepareSmithingEvent e) {
        SmithingInventory inv=e.getInventory();
        if (!isTemplate(inv.getInputTemplate())) return;
        e.setResult(smithResult(inv.getInputTemplate(),inv.getInputEquipment(),inv.getInputMineral()));
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void smith(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory() instanceof SmithingInventory inv) || e.getRawSlot()!=3 || !isTemplate(inv.getInputTemplate())) return;
        e.setCancelled(true); // Full control avoids vanilla material-component mismatches and dupes.
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ItemStack result=smithResult(inv.getInputTemplate(),inv.getInputEquipment(),inv.getInputMineral());
        if(result==null) return;
        boolean shift=e.isShiftClick();
        if(!shift && e.getCursor()!=null && !e.getCursor().getType().isAir()) return;
        if(shift && p.getInventory().firstEmpty()<0) { msg(p,"Make one free inventory slot."); return; }
        Bukkit.getScheduler().runTask(this,()->{
            if(!p.isOnline() || !isTemplate(inv.getInputTemplate())) return;
            ItemStack refreshed=smithResult(inv.getInputTemplate(),inv.getInputEquipment(),inv.getInputMineral());
            if(refreshed==null) return;
            if(shift) p.getInventory().addItem(refreshed);
            else if(p.getItemOnCursor().getType().isAir()) p.setItemOnCursor(refreshed);
            else return;
            for(int slot=0;slot<3;slot++) {
                ItemStack x=inv.getItem(slot);
                if(x!=null) { if(x.getAmount()==1) inv.setItem(slot,null); else x.setAmount(x.getAmount()-1); }
            }
            p.updateInventory();
        });
    }
    private String armorMaterial(ItemStack item) {
        if(!armor(item)) return null;
        ArmorMeta meta=(ArmorMeta)item.getItemMeta();
        if(!meta.hasTrim() || meta.getTrim()==null || !meta.getTrim().getPattern().getKey().equals(patternKey)) return null;
        String m=meta.getTrim().getMaterial().getKey().getKey();
        return TYPES.contains(m)?m:null;
    }
    private List<String> equipped(Player p) {
        LinkedHashSet<String> list=new LinkedHashSet<>();
        // Armor storage order: boots, leggings, chestplate, helmet.
        for(ItemStack item:new ItemStack[]{p.getInventory().getChestplate(), p.getInventory().getLeggings(), p.getInventory().getHelmet(), p.getInventory().getBoots()}) {
            String name=armorMaterial(item); if(name!=null) list.add(name);
        }
        return new ArrayList<>(list);
    }
    private String active(Player p) {
        List<String> list=equipped(p); if(list.isEmpty())return "";
        String chosen=selection.get(p.getUniqueId());
        return chosen!=null && list.contains(chosen)?chosen:list.get(0);
    }
    private boolean trusted(Player owner,Player other) { return trusts.getOrDefault(owner.getUniqueId(),Set.of()).contains(other.getUniqueId()); }
    private boolean canHurt(Player owner,LivingEntity enemy) { return enemy!=owner && !(enemy instanceof Player pl &&
        (trusted(owner,pl)||trusted(pl,owner)) && getConfig().getBoolean("trust.protect-from-abilities",true)); }
    private void charge(Player player,String event,LivingEntity other,double amount) {
        if(!player.hasPermission("platt.use")||amount < getConfig().getDouble("charge.combat-hit-min-damage",.5))return;
        if(other!=null && !(other instanceof Player) && !getConfig().getBoolean("charge.count-pve-hits",true))return;
        String mat=active(player); if(mat.isBlank())return;
        String type=getConfig().getString("materials."+mat+".charge.type","melee");
        if(!type.equals(event)) return;
        Charge s=state(player,mat); if(s.ready)return;
        long now=System.currentTimeMillis();
        int window=i(mat,"charge.window-seconds",getConfig().getInt("charge.global-window-seconds",12));
        if(now-s.lastHit>ms(window)) s.progress=0;
        s.lastHit=now; s.progress++;
        int goal=Math.max(1,i(mat,"charge.hits",3));
        if(s.progress>=goal) {
            s.progress=goal; s.ready=true;
            player.sendActionBar(Component.text("✦ "+mat.toUpperCase()+" READY — Sneak + Right-click or /platt use",NamedTextColor.GREEN));
            player.playSound(player.getLocation(),Sound.BLOCK_AMETHYST_BLOCK_CHIME,1,1.2f);
        } else player.sendActionBar(Component.text("✧ "+mat+" combo: "+s.progress+"/"+goal,NamedTextColor.YELLOW));
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void hit(EntityDamageByEntityEvent e) {
        LivingEntity victim=e.getEntity() instanceof LivingEntity le?le:null;
        if(victim==null)return;
        if(e.getDamager() instanceof Mob summoned && summonedOwners.containsKey(summoned.getUniqueId())) {
            Player owner=Bukkit.getPlayer(summonedOwners.get(summoned.getUniqueId()));
            if(owner!=null && !canHurt(owner,victim)){e.setCancelled(true);return;}
        }
        Player attacker=e.getDamager() instanceof Player pl?pl:null;
        boolean ranged=false;
        if(e.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) { attacker=shooter; ranged=true; }
        if(attacker!=null && victim instanceof Player vp && attacker!=vp && getConfig().getBoolean("trust.cancel-friendly-fire",true) &&
            (trusted(attacker,vp)||trusted(vp,attacker))) {e.setCancelled(true);return;}
        if(victim instanceof Player defender) {
            String mat=active(defender);
            LivingEntity hurtBy=e.getDamager() instanceof LivingEntity le ? le : e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof LivingEntity le2 ? le2 : null;
            if(defender.isBlocking()) charge(defender,"shield_block",hurtBy,Math.max(.5,e.getDamage()));
            if(e.getFinalDamage()>.0) charge(defender,"defend",hurtBy,e.getFinalDamage());
            IronGolem guardian=guardians.get(defender.getUniqueId());
            if(guardian!=null && guardian.isValid() && hurtBy!=null && canHurt(defender,hurtBy) && hurtBy instanceof Mob hostile)
                guardian.setTarget(hostile);
            if(mat.equals("gold") && state(defender,"gold").ready &&
                defender.getHealth()-e.getFinalDamage()<=8 &&
                state(defender,"gold").cooldown.getOrDefault(1,0L)<=System.currentTimeMillis()) {
                // Earned Golden Heart is automatic in an emergency, but still requires its combo.
                Bukkit.getScheduler().runTask(this,()->{if(defender.isOnline() && defender.getHealth()>0)cast(defender,1);});
            }
            if(mat.equals("iron") && ironGuard.getOrDefault(defender.getUniqueId(),0L)>System.currentTimeMillis()) {
                Vector origin=e.getDamager().getLocation().toVector().subtract(defender.getLocation().toVector()).normalize();
                if(defender.getEyeLocation().getDirection().dot(origin)>0) e.setDamage(e.getDamage()*(1-d(mat,"abilities.1.frontal-reduction",.5)));
            }
            if(mat.equals("copper") && copperConduct.remove(defender.getUniqueId())!=null && attacker!=null && attacker!=defender) {
                attacker.damage(d(mat,"abilities.1.shock-damage",3),defender);
                attacker.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,attacker.getLocation().add(0,1,0),22);
                for(LivingEntity chained:nearby(defender,attacker.getLocation(),4))
                    if(chained!=attacker && chained.isInWater()) chained.damage(d(mat,"abilities.1.shock-damage",3)*.5,defender);
            }
            if(mat.equals("lapis") && lapisSurge.getOrDefault(defender.getUniqueId(),0L)>System.currentTimeMillis() &&
                defender.getInventory().getChestplate()!=null && !defender.getInventory().getChestplate().getEnchantments().isEmpty()) e.setDamage(e.getDamage()*.90);
        }
        if(attacker!=null && canHurt(attacker,victim)) {
            lastEnemy.put(attacker.getUniqueId(),victim.getUniqueId());
            String mat=active(attacker);
            if(ranged) charge(attacker,"ranged",victim,e.getFinalDamage());
            else {
                charge(attacker,"melee",victim,e.getFinalDamage());
                if(attacker.isSprinting())charge(attacker,"sprint_melee",victim,e.getFinalDamage());
                if(attacker.isSneaking())charge(attacker,"sneak_melee",victim,e.getFinalDamage());
                if(!attacker.isOnGround() && attacker.getVelocity().getY()<0)charge(attacker,"airborne_melee",victim,e.getFinalDamage());
                if(!attacker.isOnGround() && attacker.getFallDistance()>0)charge(attacker,"critical_melee",victim,e.getFinalDamage());
                Vector behind=attacker.getLocation().toVector().subtract(victim.getLocation().toVector()).normalize();
                if(victim.getEyeLocation().getDirection().dot(behind)<-.45)charge(attacker,"rear_melee",victim,e.getFinalDamage());
            }
            if(mat.equals("lapis") && lapisSurge.getOrDefault(attacker.getUniqueId(),0L)>System.currentTimeMillis() &&
                !attacker.getInventory().getItemInMainHand().getEnchantments().isEmpty())
                e.setDamage(e.getDamage()+d(mat,"abilities.1.damage-bonus",1.5));
            if(mat.equals("diamond") && diamondUntil.getOrDefault(attacker.getUniqueId(),0L)>System.currentTimeMillis() &&
                attacker.getInventory().getItemInMainHand().getType().name().endsWith("_SWORD") && diamondHits.getOrDefault(attacker.getUniqueId(),0)>0) {
                e.setDamage(e.getDamage()+d(mat,"abilities.1.bonus-damage",2));
                diamondHits.compute(attacker.getUniqueId(),(id,v)->Math.max(0,(v==null?1:v)-1));
            }
            if(mat.equals("netherite") && netheriteTank.getOrDefault(attacker.getUniqueId(),0L)>System.currentTimeMillis() &&
               attacker.getInventory().getItemInMainHand().getType().name().endsWith("_AXE") && victim instanceof Player pl && pl.isBlocking())
                pl.setCooldown(Material.SHIELD,100);
            if(mat.equals("breeze") && breezeSmash.getOrDefault(attacker.getUniqueId(),0L)>System.currentTimeMillis() &&
                !ranged && attacker.getInventory().getItemInMainHand().getType().name().endsWith("_SWORD") && attacker.getFallDistance()>1) {
                double extra=Math.min(d(mat,"abilities.2.max-bonus-damage",11),attacker.getFallDistance()*d(mat,"abilities.2.fall-damage-multiplier",1.3));
                e.setDamage(e.getDamage()+extra); breezeSmash.remove(attacker.getUniqueId());
                blast(attacker,victim.getLocation(),4,1.1,0,Particle.GUST);
                safeFall.put(attacker.getUniqueId(),System.currentTimeMillis()+ms(2));
            }
            if(mat.equals("echo") && echoMarkedUntil.getOrDefault(attacker.getUniqueId(),0L)>System.currentTimeMillis() &&
                !ranged && attacker.getInventory().getItemInMainHand().getType().name().endsWith("_SWORD")) {
                Map<UUID,Integer> marked=echoMarks.computeIfAbsent(attacker.getUniqueId(),key->new HashMap<>());
                int hits=marked.merge(victim.getUniqueId(),1,Integer::sum);
                victim.getWorld().spawnParticle(Particle.SCULK_SOUL,victim.getLocation().add(0,1,0),9);
                if(hits>=i(mat,"abilities.2.marks-required",3)) {
                    e.setDamage(e.getDamage()+d(mat,"abilities.2.bonus-damage",4));
                    marked.remove(victim.getUniqueId()); victim.setVelocity(victim.getVelocity().add(new Vector(0,.35,0)));
                    victim.getWorld().playSound(victim.getLocation(),Sound.ENTITY_WARDEN_SONIC_BOOM,1,.8f);
                }
            }
        }
    }
    @EventHandler(ignoreCancelled=true) public void hurt(EntityDamageEvent e) {
        if(!(e.getEntity() instanceof Player p))return;
        if(e.getCause()==EntityDamageEvent.DamageCause.FALL && safeFall.getOrDefault(p.getUniqueId(),0L)>System.currentTimeMillis()) e.setCancelled(true);
        if(e.getCause()==EntityDamageEvent.DamageCause.FIRE || e.getCause()==EntityDamageEvent.DamageCause.LAVA) {
            if(netheriteTank.getOrDefault(p.getUniqueId(),0L)>System.currentTimeMillis())e.setCancelled(true);
        }
    }
    @EventHandler(ignoreCancelled=true) public void velocity(PlayerVelocityEvent e) {
        if(netheriteTank.getOrDefault(e.getPlayer().getUniqueId(),0L)>System.currentTimeMillis())e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void durability(PlayerItemDamageEvent e) {
        if(diamondUntil.getOrDefault(e.getPlayer().getUniqueId(),0L)>System.currentTimeMillis() && active(e.getPlayer()).equals("diamond")) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void piglinTarget(EntityTargetLivingEntityEvent e) {
        if(e.getEntity() instanceof Piglin && !(e.getEntity() instanceof PiglinBrute) && e.getTarget() instanceof Player p && equipped(p).contains("gold"))
            e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void villagerDiscount(PlayerInteractEntityEvent e) {
        if(!(e.getRightClicked() instanceof AbstractVillager) || !active(e.getPlayer()).equals("emerald"))return;
        Player p=e.getPlayer();long day=p.getWorld().getFullTime()/24000L;
        if(emeraldTradeDay.getOrDefault(p.getUniqueId(),-1L)==day)return;
        emeraldTradeDay.put(p.getUniqueId(),day);
        potion(p,PotionEffectType.HERO_OF_THE_VILLAGE,
            (int)(getConfig().getDouble("materials.emerald.abilities.1.trade-discount-seconds",15)*20),0);
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        if(getConfig().getBoolean("charge.reset-on-death",true))
            for(Charge charge:states.getOrDefault(e.getEntity().getUniqueId(),Map.of()).values()) { charge.progress=0; charge.ready=false; }
    }
    @EventHandler(ignoreCancelled=true) public void interact(PlayerInteractEvent e) {
        Player p=e.getPlayer(); if(!p.isSneaking()||e.getHand()!=EquipmentSlot.HAND)return;
        boolean right=e.getAction()==Action.RIGHT_CLICK_AIR||e.getAction()==Action.RIGHT_CLICK_BLOCK;
        boolean left=e.getAction()==Action.LEFT_CLICK_AIR||e.getAction()==Action.LEFT_CLICK_BLOCK;
        if((right && getConfig().getBoolean("activation.sneak-right-click",true)) ||
            (left && getConfig().getBoolean("activation.sneak-left-click-special",true) && List.of("breeze","echo").contains(active(p)))) {
            e.setCancelled(true); cast(p,left?2:1);
        }
    }
    private void cast(Player p,int ability) {
        if(!p.hasPermission("platt.use")) {msg(p,"You lack platt.use.");return;}
        String mat=active(p); if(mat.isBlank()) {msg(p,"Wear a Platt-trimmed armor piece first.");return;}
        if(ability==2 && !List.of("breeze","echo").contains(mat)) {msg(p,"Only Breeze and Echo have a second ability.");return;}
        Charge s=state(p,mat);
        long now=System.currentTimeMillis();
        if(!s.ready) {msg(p,"Charge your "+mat+" trim first: "+s.progress+"/"+i(mat,"charge.hits",3));return;}
        long remain=s.cooldown.getOrDefault(ability,0L)-now;
        if(remain>0) {msg(p,"Cooldown: "+((remain+999)/1000)+" seconds. Charge is still saved.");return;}
        if(!usePower(p,mat,ability)) {msg(p,"That power needs a valid target, tool or safe place.");return;}
        s.ready=false; s.progress=0;
        s.cooldown.put(ability,now+secs(mat,ability));
        p.playSound(p.getLocation(),Sound.BLOCK_BEACON_ACTIVATE,0.5f,1.5f);
        p.sendActionBar(Component.text("✦ "+mat.toUpperCase()+" activated! Recharge by combat.",NamedTextColor.AQUA));
    }
    private int ticks(String m,int a) {return (int)Math.max(1,d(m,"abilities."+a+".duration",6)*20);}
    private List<LivingEntity> nearby(Player p,Location loc,double radius) {
        List<LivingEntity> list=new ArrayList<>();
        for(Entity en:loc.getWorld().getNearbyEntities(loc,radius,radius,radius))
            if(en instanceof LivingEntity living && canHurt(p,living) && living.getLocation().distanceSquared(loc)<=radius*radius) list.add(living);
        return list;
    }
    private void blast(Player p,Location loc,double rad,double knock,double damage,Particle particle) {
        loc.getWorld().spawnParticle(particle,loc.clone().add(0,1,0),getConfig().getInt("particles.count",22));
        for(LivingEntity enemy:nearby(p,loc,rad)) {
            Vector away=enemy.getLocation().toVector().subtract(loc.toVector());
            if(away.lengthSquared()<.01)away=p.getLocation().getDirection();
            enemy.setVelocity(away.normalize().multiply(knock).setY(.3));
            if(damage>0)enemy.damage(damage,p);
        }
    }
    private void potion(Player p,PotionEffectType type,int duration,int amplifier) {
        p.addPotionEffect(new PotionEffect(type,duration,amplifier,false,true,true));
    }
    private boolean usePower(Player p,String m,int ability) {
        Location loc=p.getLocation(); World w=p.getWorld(); long now=System.currentTimeMillis();
        switch(m) {
            case "lapis" -> {
                lapisSurge.put(p.getUniqueId(),now+ms(d(m,"abilities.1.duration",7)));
                if(p.getInventory().getItemInMainHand().getType().name().endsWith("_PICKAXE") && !p.getInventory().getItemInMainHand().getEnchantments().isEmpty())
                    potion(p,PotionEffectType.HASTE,ticks(m,1),1);
                w.spawnParticle(Particle.ENCHANT,loc.clone().add(0,1,0),42);
            }
            case "iron" -> {
                ironGuard.put(p.getUniqueId(),now+ms(d(m,"abilities.1.duration",4)));
                potion(p,PotionEffectType.SLOWNESS,ticks(m,1),0);
                w.spawnParticle(Particle.CRIT,loc.clone().add(0,1,0),30);
                Bukkit.getScheduler().runTaskLater(this,()->{
                    if(p.isOnline()) blast(p,p.getLocation(),3,.75,0,Particle.CLOUD);
                },ticks(m,1));
            }
            case "redstone" -> {
                if(getConfig().getBoolean("materials.redstone.abilities.1.power-redstone-components",false)) powerNearby(loc);
                if(p.getLocation().getDirection().getY()<-.6) {
                    Vector v=p.getVelocity(); p.setVelocity(v.setY(d(m,"abilities.1.self-launch",1.1)));
                    safeFall.put(p.getUniqueId(),now+ms(8));
                } else blast(p,loc,d(m,"abilities.1.radius",5),d(m,"abilities.1.knockback",1.7),0,Particle.ELECTRIC_SPARK);
            }
            case "copper" -> {
                copperConduct.put(p.getUniqueId(),now+ms(d(m,"abilities.1.duration",6)));
                w.spawnParticle(Particle.ELECTRIC_SPARK,loc.clone().add(0,1,0),42);
                if(w.hasStorm() && w.isThundering()) {
                    LivingEntity target=targetLiving(p,15);
                    if(target!=null && canHurt(p,target)) {
                        w.strikeLightningEffect(target.getLocation());
                        target.damage(d(m,"abilities.1.shock-damage",3),p);
                    }
                }
            }
            case "gold" -> {
                potion(p,PotionEffectType.ABSORPTION,(int)(d(m,"abilities.1.absorption-seconds",7)*20),1);
                potion(p,PotionEffectType.REGENERATION,(int)(d(m,"abilities.1.regeneration-seconds",3)*20),1);
                w.spawnParticle(Particle.TOTEM_OF_UNDYING,loc.clone().add(0,1,0),45);
            }
            case "emerald" -> {
                IronGolem golem=w.spawn(loc.clone().add(1,0,0),IronGolem.class);
                golem.customName(Component.text("Village Guardian",NamedTextColor.GREEN));
                golem.setCustomNameVisible(true); golem.setPlayerCreated(true);
                summonedOwners.put(golem.getUniqueId(),p.getUniqueId());guardians.put(p.getUniqueId(),golem);
                for(LivingEntity enemy:nearby(p,loc,12)) if(enemy instanceof Monster) {golem.setTarget(enemy);break;}
                Bukkit.getScheduler().runTaskLater(this,()->{ if(golem.isValid())golem.remove(); summonedOwners.remove(golem.getUniqueId());guardians.remove(p.getUniqueId(),golem); },ticks(m,1));
            }
            case "diamond" -> {
                diamondUntil.put(p.getUniqueId(),now+ms(d(m,"abilities.1.duration",8)));
                diamondHits.put(p.getUniqueId(),i(m,"abilities.1.sword-hits",3));
                if(p.getInventory().getItemInMainHand().getType().name().endsWith("_PICKAXE")) potion(p,PotionEffectType.HASTE,ticks(m,1),1);
                w.spawnParticle(Particle.CRIT,loc.clone().add(0,1,0),30);
            }
            case "netherite" -> {
                netheriteTank.put(p.getUniqueId(),now+ms(d(m,"abilities.1.duration",6)));
                potion(p,PotionEffectType.FIRE_RESISTANCE,ticks(m,1),0);
                potion(p,PotionEffectType.SLOWNESS,ticks(m,1),0);
                w.spawnParticle(Particle.LAVA,loc.clone().add(0,1,0),20);
            }
            case "quartz" -> {
                lastActivation.put(p.getUniqueId(),now+ms(d(m,"abilities.1.duration",5)));
                scanQuartz(p,now);
            }
            case "amethyst" -> {
                double rad=d(m,"abilities.1.radius",4);
                blast(p,loc,rad,.12,0,Particle.END_ROD);
                for(LivingEntity enemy:nearby(p,loc,rad)) enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,ticks(m,1),2));
                // Visual-only crystals; does not replace blocks or bypass region protection.
                for(int n=0;n<12;n++){
                    double angle=n*2*Math.PI/12;
                    Location crystal=loc.clone().add(Math.cos(angle)*rad,1,Math.sin(angle)*rad);
                    w.spawnParticle(Particle.END_ROD,crystal,15,.1,1.1,.1,.04);
                }
                w.playSound(loc,Sound.BLOCK_AMETHYST_CLUSTER_BREAK,1,1);
            }
            case "resin" -> {
                Creaking creaking=w.spawn(loc.clone().add(1,0,1),Creaking.class);
                creaking.customName(Component.text("Awakened Creaking",NamedTextColor.GOLD));
                creaking.setCustomNameVisible(true);
                summonedOwners.put(creaking.getUniqueId(),p.getUniqueId());
                Player enemy=null;
                for(LivingEntity en:nearby(p,loc,14)) if(en instanceof Player pl){enemy=pl;break;}
                if(enemy!=null) creaking.activate(enemy);
                Bukkit.getScheduler().runTaskLater(this,()->{if(creaking.isValid())creaking.remove();summonedOwners.remove(creaking.getUniqueId());},ticks(m,1));
            }
            case "breeze" -> {
                if(ability==1) {
                    Vector v=p.getVelocity();p.setVelocity(v.setY(d(m,"abilities.1.launch-power",1.45)));
                    safeFall.put(p.getUniqueId(),now+ms(d(m,"abilities.1.safe-fall-seconds",15)));
                    w.spawnParticle(Particle.GUST,loc,50);w.playSound(loc,Sound.ENTITY_BREEZE_SHOOT,1,1);
                } else {
                    if(!p.getInventory().getItemInMainHand().getType().name().endsWith("_SWORD")) return false;
                    breezeSmash.put(p.getUniqueId(),now+ms(d(m,"abilities.2.duration",12)));
                    p.sendActionBar(Component.text("Your next FALLING sword strike is Mace-powered!",NamedTextColor.AQUA));
                }
            }
            case "echo" -> {
                if(ability==1) {
                    LivingEntity target=targetLiving(p,d(m,"abilities.1.range",18));
                    if(target==null||!canHurt(p,target))return false;
                    Vector ray=p.getEyeLocation().getDirection().normalize();
                    for(double r=1;r<=d(m,"abilities.1.range",18);r+=.6) {
                        Location spot=p.getEyeLocation().clone().add(ray.clone().multiply(r));
                        w.spawnParticle(Particle.SONIC_BOOM,spot,1,0,0,0,0);
                    }
                    target.damage(d(m,"abilities.1.damage",6),DamageSource.builder(DamageType.SONIC_BOOM).withDirectEntity(p).withCausingEntity(p).build()); // Vanilla sonic-boom damage bypasses armor/shields.
                    target.setVelocity(target.getVelocity().add(ray.multiply(d(m,"abilities.1.knockback",.8))));
                    w.playSound(loc,Sound.ENTITY_WARDEN_SONIC_BOOM,1,1);
                } else {
                    echoMarkedUntil.put(p.getUniqueId(),now+ms(d(m,"abilities.2.duration",8)));
                    echoMarks.put(p.getUniqueId(),new HashMap<>());
                    w.spawnParticle(Particle.SCULK_SOUL,loc.clone().add(0,1,0),35);
                }
            }
            default -> {return false;}
        }
        return true;
    }
    private void powerNearby(Location at) {
        // Explicit opt-in because switching live components can affect neighboring contraptions.
        World w=at.getWorld();int bx=at.getBlockX(),by=at.getBlockY(),bz=at.getBlockZ();
        for(int x=-2;x<=2;x++)for(int y=-1;y<=1;y++)for(int z=-2;z<=2;z++){
            Block block=w.getBlockAt(bx+x,by+y,bz+z);
            if(block.getType()!=Material.LEVER && !block.getType().name().endsWith("_BUTTON"))continue;
            BlockData original=block.getBlockData();
            if(!(original instanceof Powerable powerable) || powerable.isPowered())continue;
            BlockData powered=original.clone();((Powerable)powered).setPowered(true);
            block.setBlockData(powered,false);
            Bukkit.getScheduler().runTaskLater(this,()->{
                if(block.getBlockData() instanceof Powerable powerNow && powerNow.isPowered())
                    block.setBlockData(original,false);
            },40L);
        }
    }
    private LivingEntity targetLiving(Player p,double range) {
        // Simple ray against entity boxes. No line of sight requirement for sonic boom.
        Location start=p.getEyeLocation(); Vector ray=start.getDirection().normalize();
        LivingEntity found=null; double nearest=Double.MAX_VALUE;
        for(LivingEntity entity:nearby(p,p.getLocation(),range)) {
            Vector offset=entity.getLocation().add(0,1,0).toVector().subtract(start.toVector());
            double along=offset.dot(ray);
            if(along<0||along>range)continue;
            double dist=offset.clone().subtract(ray.clone().multiply(along)).length();
            if(dist<1.1 && along<nearest){found=entity;nearest=along;}
        }
        return found;
    }
    private void scanQuartz(Player p,long now) {
        if(lastActivation.getOrDefault(p.getUniqueId(),0L)<=now)return;
        double rad=d("quartz","abilities.1.radius",15);
        for(LivingEntity ent:nearby(p,p.getLocation(),rad)) if(ent instanceof Player target) {
            if(target.isSneaking() && target.getVelocity().lengthSquared()<.02)continue;
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,40,0));
            p.playSound(p.getLocation(),Sound.BLOCK_NOTE_BLOCK_HAT,.12f,1.4f);
        }
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        switch(command.getName().toLowerCase(Locale.ROOT)) {
            case "trust" -> {
                if(!(sender instanceof Player p)) {msg(sender,"Use this command in game.");return true;}
                if(args.length==0) {msg(p,"/trust add <name>, /trust remove <name>, /trust list");return true;}
                Set<UUID> list=trusts.computeIfAbsent(p.getUniqueId(),k->new HashSet<>());
                if(args[0].equalsIgnoreCase("list")) {
                    if(list.isEmpty())msg(p,"Your trust list is empty.");
                    else msg(p,"Trusted: "+String.join(", ",list.stream().map(id->Bukkit.getOfflinePlayer(id).getName()==null?id.toString():Bukkit.getOfflinePlayer(id).getName()).toList()));
                    return true;
                }
                if(args.length<2) {msg(p,"/trust "+args[0]+" <player>");return true;}
                OfflinePlayer op=Bukkit.getOfflinePlayer(args[1]);
                if(!op.hasPlayedBefore() && !op.isOnline()) {msg(p,"That player hasn't joined this server.");return true;}
                if(op.getUniqueId().equals(p.getUniqueId())) {msg(p,"You cannot trust yourself.");return true;}
                if(args[0].equalsIgnoreCase("add")) {list.add(op.getUniqueId());msg(p,"Trusted "+op.getName());saveData();}
                else if(args[0].equalsIgnoreCase("remove")) {list.remove(op.getUniqueId());msg(p,"Removed "+op.getName());saveData();}
                else msg(p,"Use /trust add, remove or list.");
                return true;
            }
            case "cooldown" -> {
                if(args.length==0||!args[0].equalsIgnoreCase("reset")) {msg(sender,"/cooldown reset [player] [material|all]");return true;}
                if(!sender.hasPermission("platt.admin")){msg(sender,"Requires platt.admin.");return true;}
                Player p=args.length>=2?Bukkit.getPlayerExact(args[1]):sender instanceof Player self?self:null;
                if(p==null) {msg(sender,"Player must be online.");return true;}
                String mat=args.length>=3?args[2].toLowerCase(Locale.ROOT):"all";
                if(!mat.equals("all")&&!TYPES.contains(mat)){msg(sender,"Unknown material.");return true;}
                List<String> targets=mat.equals("all")?TYPES:List.of(mat);
                for(String target:targets) {
                    Charge s=state(p,target); s.cooldown.clear();s.progress=0;
                    s.ready=getConfig().getBoolean("admin.cooldown-reset-grants-ready",true);
                }
                msg(sender,"Reset cooldowns for "+p.getName()+" ("+mat+").");saveData();return true;
            }
            case "platt" -> {
                if(args.length==0) {msg(sender,"/platt status | select <material> | use [1|2] | template [player] | reload");return true;}
                String sub=args[0].toLowerCase(Locale.ROOT);
                if(sub.equals("reload")||sub.equals("template")) {
                    if(!sender.hasPermission("platt.admin")){msg(sender,"Requires platt.admin.");return true;}
                    if(sub.equals("reload")){reloadConfig();msg(sender,"Config reloaded. Restart needed after datapack changes.");return true;}
                    Player target=args.length>=2?Bukkit.getPlayerExact(args[1]):sender instanceof Player pp?pp:null;
                    if(target==null){msg(sender,"Target must be online.");return true;}
                    target.getInventory().addItem(template());msg(sender,"Gave template to "+target.getName());return true;
                }
                if(!(sender instanceof Player p)){msg(sender,"Requires a player.");return true;}
                if(sub.equals("status")) {
                    String mat=active(p);
                    if(mat.isEmpty()){msg(p,"No Platt armor equipped.");return true;}
                    Charge s=state(p,mat);long now=System.currentTimeMillis();
                    msg(p,"Active: "+mat+" | Combo "+s.progress+"/"+i(mat,"charge.hits",3)+" | "+(s.ready?"READY":"charging"));
                    for(int n=1;n<=(List.of("breeze","echo").contains(mat)?2:1);n++)
                        msg(p,"Power "+n+": "+Math.max(0,(s.cooldown.getOrDefault(n,0L)-now+999)/1000)+"s cooldown");
                    return true;
                }
                if(sub.equals("select")) {
                    if(args.length<2){msg(p,"Wear one of: "+String.join(", ",equipped(p)));return true;}
                    String m=args[1].toLowerCase(Locale.ROOT);
                    if(!equipped(p).contains(m)){msg(p,"You aren't wearing "+m+" Platt armor.");return true;}
                    selection.put(p.getUniqueId(),m);msg(p,"Selected "+m+".");saveData();return true;
                }
                if(sub.equals("use")) {cast(p,args.length>=2 && args[1].equals("2")?2:1);return true;}
                msg(p,"Use /platt status, select, use, template, reload.");return true;
            }
        }
        return false;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if(args.length==1) return switch(command.getName()){
            case "trust" -> filter(List.of("add","remove","list"),args[0]);
            case "cooldown" -> filter(List.of("reset"),args[0]);
            default -> filter(List.of("status","select","use","template","reload"),args[0]);
        };
        if(command.getName().equals("platt") && args[0].equalsIgnoreCase("select") && args.length==2 && sender instanceof Player p)
            return filter(equipped(p),args[1]);
        if(command.getName().equals("platt") && args[0].equalsIgnoreCase("use") && args.length==2) return filter(List.of("1","2"),args[1]);
        if(command.getName().equals("cooldown") && args.length==3)return filter(new ArrayList<>(){{add("all");addAll(TYPES);}},args[2]);
        if(args.length==2 && (command.getName().equals("trust")||command.getName().equals("cooldown")||command.getName().equals("platt")))
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),args[1]);
        return List.of();
    }
    private List<String> filter(List<String> opts,String prefix) {return opts.stream().filter(x->x.startsWith(prefix.toLowerCase(Locale.ROOT))).toList();}
    private void loadData() {
        if(!saveFile.isFile())return;
        YamlConfiguration data=YamlConfiguration.loadConfiguration(saveFile);
        ConfigurationSection players=data.getConfigurationSection("players"); if(players==null)return;
        for(String key:players.getKeys(false))try{
            UUID id=UUID.fromString(key);
            String sel=data.getString("players."+key+".selection");if(sel!=null)selection.put(id,sel);
            Set<UUID> trusted=new HashSet<>();for(String raw:data.getStringList("players."+key+".trust"))trusted.add(UUID.fromString(raw));trusts.put(id,trusted);
            for(String mat:TYPES){
                String base="players."+key+".states."+mat;
                if(!data.contains(base))continue;
                Charge c=states.computeIfAbsent(id,missing->new HashMap<>()).computeIfAbsent(mat,missing->new Charge());
                c.progress=data.getInt(base+".progress");c.lastHit=data.getLong(base+".last-hit");c.ready=data.getBoolean(base+".ready");
                c.cooldown.put(1,data.getLong(base+".cooldown-1"));c.cooldown.put(2,data.getLong(base+".cooldown-2"));
            }
        }catch(IllegalArgumentException bad){getLogger().warning("Ignoring corrupt player UUID: "+key);}
    }
    private void saveData() {
        if(saveFile==null)return;
        YamlConfiguration data=new YamlConfiguration();
        Set<UUID> all=new HashSet<>(states.keySet());all.addAll(trusts.keySet());all.addAll(selection.keySet());
        for(UUID id:all){
            String base="players."+id;
            data.set(base+".selection",selection.get(id));
            data.set(base+".trust",trusts.getOrDefault(id,Set.of()).stream().map(UUID::toString).toList());
            for(Map.Entry<String,Charge> ent:states.getOrDefault(id,Map.of()).entrySet()){
                Charge c=ent.getValue();String at=base+".states."+ent.getKey();
                data.set(at+".progress",c.progress);data.set(at+".last-hit",c.lastHit);data.set(at+".ready",c.ready);
                data.set(at+".cooldown-1",c.cooldown.getOrDefault(1,0L));data.set(at+".cooldown-2",c.cooldown.getOrDefault(2,0L));
            }
        }
        try{data.save(saveFile);}catch(IOException ex){getLogger().severe("Failed to save players.yml: "+ex.getMessage());}
    }
}
