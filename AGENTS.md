<!-- pureapi-agents a5c62021a6e548446820b05005688191295d6a72904b65cfc46c878ffceea2c0 -->
# AGENTS.md

Notes for AI coding agents working on this script.

## What this is

This folder is a PureApi **Scripter** script for the RuneLite (Old School RuneScape) client. The
Scripter plugin compiles every `.java` file in this folder (and its subfolders, for packages) in
memory and runs the result inside the client. There is no build file: don't add Gradle or Maven
files, and don't try to build or run the script outside the client.

- The main class is the one non-abstract subclass of `org.pureapps.api.scripts.Script`. With more
  than one, `@ScriptManifest` picks it.
- Every other file in the folder is a resource. Read it with `openResource("path")` (an
  `InputStream`, or null). It also works when the script is imported from GitHub, which the
  Scripter keeps in memory only: such a script has no folder, so `resource("path")` (a `File`)
  only works for local scripts, and only when the script is trusted (see Sandbox).
- A script can also live in a source folder linked into the Scripter (its Folders button), e.g. a
  project's `src/main/java` with several scripts. Each non-abstract `Script` subclass there is its
  own script, compiled from its package root with the shared code but not the other scripts'
  main files and folders. Resources are also found in the sibling `src/main/resources`.
- `.idea/`, `out/` and `*.iml` are IntelliJ files written by the Scripter's IntelliJ button. Leave
  them alone; they are not compiled.
- With "Hot Reload" on, saving any file recompiles the script and restarts it. A failed compile
  keeps the old version running, and the errors show in the Scripter output.
- The PureApi and RuneLite sources are not in this folder. Look up the PureApi API instead of
  guessing it: in the Scripter's AI chat use the `search_api` and `read_api` tools; as a CLI agent
  read the Javadoc HTML in `~/.runelite/pureapi/javadoc` (start at `allclasses-index.html`, or
  grep `member-search-index.js`; a class's page is `org/pureapps/api/Bank.html` and so on). Open
  the folder in IntelliJ (the Scripter's IntelliJ button) for completion. RuneLite's own classes
  are documented at https://static.runelite.net/runelite-api/apidocs/.

## Script lifecycle

```java
@ScriptManifest(name = "My Script", version = "1.0", description = "...")
public class Main extends Script {
    @Override public void onStart() { }   // once, before the first onLoop
    @Override public void onLoop() { }    // once per game tick (600 ms)
    @Override public void onStop() { }    // once, when the script stops for any reason
}
```

- `onStart`, `onLoop`, `onStop` and `@Subscribe` methods run on the **client thread**. Never sleep,
  wait or block in them: it freezes the game. Do one step per tick and keep state in fields.
- `onLoop` is ticked by PureApi's `StateManager`, so the break handler pauses it. It runs a human
  reaction time (40 to 450 ms) after each game tick, not the moment the tick arrives.
- Any exception thrown from the script stops it.
- Fields set for you: `api` (the PureApi facade), `client` (RuneLite `Client`), `clientThread`.
- `@Inject` fields and `@Subscribe` methods (RuneLite events from `net.runelite.api.events`) work
  like in a RuneLite plugin.
- Helpers on `Script`: `status(text)` (the overlay's state line), `log(format, args...)` (SLF4J
  style `{}` placeholders, goes to the Scripter output and run log), `stop()`,
  `addOverlay(overlay)` (removed on stop), `openResource(path)`, `resource(path)` (local, trusted
  scripts only).

## Sandbox

Every script runs sandboxed until the user marks it **Trusted** on its Scripter page. Before a
sandboxed script loads, the Scripter checks its bytecode against an allowlist and refuses to start
it if it uses anything else; the output lists each use as `File.java:line: <class or member> is
not allowed in sandboxed scripts`. Write scripts that pass the sandbox:

- Allowed: `java.lang` basics (`String`, `Math`, boxed types, exceptions, `StringBuilder`),
  `java.util` collections, `Optional`, `Random`, `java.util.function`, `java.util.stream`,
  `java.util.regex`, `java.time`, `java.text`, `java.math`, drawing classes from `java.awt`
  (`Graphics2D`, `Color`, `Font`, `Rectangle`, `Point`, `Polygon`, `BasicStroke`, `java.awt.geom`),
  most of `net.runelite.**` (the API, overlays, infoboxes, `ItemManager`, `ItemStats`, `SpriteManager`,
  `NPCManager`, `ChatMessageManager`, `Notifier`, `client.util`, ...), PureApi's `@ShowIf`, `@Divider`,
  `@StartButton`, `@ChoiceList`, `@Inject`, and the PureApi facades on `api`.
- Not allowed: files (`java.io.File`, `java.nio.file`, `resource(path)`: use `openResource(path)`),
  the network, threads and executors (so not `api.waits.waitUntil`), reflection
  (`Class.forName`, `getClassLoader`, `java.lang.reflect`), `System.getProperty`/`getenv`/`exit`,
  `api.config` (use the script's own `@ConfigGroup` interface), Swing and `org.pureapps.api.ui`,
  `ConfigManager`, `EventBus`, `KeyManager`, RuneLite's plugins, web clients (`*Client` classes),
  party and Discord, Swing (`client.ui` frames and components), and the client's hooks, canvas and socket.
- Lambdas, method references, records, enums, `switch` patterns and string concatenation work.
- When a script truly needs something the sandbox refuses, say so to the user: only they can
  trust the script, and they should read its code first.

## Settings

A script can have settings: one public interface in this folder that extends
`net.runelite.client.config.Config` and has `@ConfigGroup("unique-group")`, with `@ConfigItem`
methods like a RuneLite plugin config. Get it with an `@Inject` field of that type. The Scripter
page shows it, and changes apply to a running script at once.
`@ShowIf(key = "otherKey", value = "...")` (`org.pureapps.api.ui.plugins`) on an item or
`@ConfigSection` field shows it only while another item has one of the given raw values,
`@Divider("Label")` draws a divider above an item, and `@StartButton` draws a boolean item as a
Start/Stop button. `@ChoiceList(MyEnum.class)` on a `String` item draws an ordered pick list of the
enum's constants (switches to pick, drag to reorder); the value is the picked `name()`s in order,
comma-separated. An enum implementing `ChoiceList.Status` (`color()`, e.g. `Theme.PRIMARY`) has
each name drawn in its own colour; returning false from its `selectable()` lists that constant last,
switched off (disabled, and dropped from the saved value). They work in sandboxed scripts too.

## PureApi quick reference

Use the facades on `api`. Interaction methods return `true` when the action was sent; check the
game state on a later tick to see if it worked.

- `api.player`: `get()`, `getWorldLocation()` (null with no local player, e.g. on the login screen), `realLocation()` (the real world tile inside an instance, for distances to world tiles), `isMoving()`, `isAnimating()`, `isInteracting()`, `inCutscene()`, `isIronman()`.
- `api.npcs`: `search()` (an `NpcQuery`), `interact(name, action)`, `attack(name)`, `talk(name)`.
- `api.objects`: `search()`, `searchAll()` (every kind of tile object), `interact(name, action)`,
  `interact(tileObject[, action])`. Doors are often walls: also try `api.walls`.
- `api.groundItems`, `api.players`, `api.widgets`: search and interact the same way. Add `.takeable()` to a ground item search before taking: it drops other players' items on ironman accounts, which the game won't let them take (`pickUp` already does). `api.widgets.make(output, quantity)` picks a product in the "How many do you wish to make?" dialog; pass the quantity, since without it the dialog's own (often 1) is used.
- `api.inventory`: `contains`, `containsOtherThan(ids or names)` (junk to `depositAllExcept` the same keep list), `count`, `quantity` (`quantity(id)` and `contains(id)` read the inventory container, so they stay right while the GE replaces the inventory panel), `isFull`, `emptySlots`, `interact(name, action)`,
  `eat` (`ticksSinceEat()` counts from the last eat click by any caller, so two runners don't eat in one food delay), `drink` (also by `Potion`; `Food.pick(api, trip)` picks the best food owned; `food.edible(api)` counts unnoted ones, `food.noted(api)` finds a noted stack), `equip`, `drop`, `dropAll`, `useOnItem`, `useOnNpc`,
  `useOnObject` (any tile object, doors and other walls too).
- `api.bank`: `isOpen`, `open()`, `openClosest()` (the bank nearest by walking; it walks in the background until `cancelActions()`), `openForTrip(tile)` (the bank on the shortest way to `tile`), `close`, `contains`, `count`, `withdraw(name, qty)`,
  `withdrawAll`, `deposit`, `depositAll`, `depositAllExcept`, `equip(id)` (wield from the open bank's inventory), `setNoting`, `cached()` (last seen bank,
  readable while closed).
  `api.bank.reconcile(Loadout)` brings the open bank's inventory to a target one click per call
  (`Loadout.carrying(Map.of(id, qty))`, `Loadout.keeping("*pickaxe")`, `Loadout.nothing().strippingWorn()`),
  returning false once done; use it instead of separate deposit and withdraw calls.
- `api.walker`: `walkTo(worldPoint)` walks on its own thread (doors, transports, teleports);
  `walkToNearest(points)` walks to the one nearest by path (`getLastTarget()` says which);
  poll `isWalking()`, `isAt(point)` and `getState()` from `onLoop`; `cancel()`; `allows(transport)` says
  whether the walker's options let a walk use a transport. Transports with
  a fare (tolls, carts, ships) are only used while the inventory holds the coins. Walks work inside
  instances too: pass real world tiles. Inside an instance `getWorldLocation()` gives instance tiles;
  `Instances.toReal(client, point)` / `toScene(client, real)` / `playerLocation(client)` convert.
  Walks of a player below a `DangerArea`'s safe combat level path around it (e.g. the dark wizards
  south of Varrock, the Draynor jail guards, Lizardman Canyon, the Catacombs of Kourend) unless the
  walk starts or ends inside it. A run-past area (White Wolf Mountain) is crossed instead: the walk
  goes up with run off (`api.run.isHeldOff()`) until the energy covers running across, waits at its
  edge for it, then runs through.
- `api.equipment`, `api.skills`, `api.combat`, `api.prayers`, `api.spells`, `api.dialogue`,
  `api.lamps` (`use(skill)` once per tick: rubs an Antique lamp, picks the skill, confirms; `hasLamp()`),
  `api.shop` (`open(Shops.Shop)` / `owner(shop)` find the owner by NPC id, not name), `api.ge`, `api.trade`, `api.depositBox`, `api.tabs`, `api.run`, `api.camera`,
  `api.keyboard`, `api.teleports`, `api.fairyRings`, `api.stealth` (`follow(npc)` once a tick tails an NPC unseen), `api.sailing` (boats: `isOnBoat()`, `sailTo(tile, within)` once a tick until `ARRIVED` (`sailThrough` to pass a point at full speed), `dock()`, `board()` (or `board(type)` with several boats: the gangplank's Board opens the boat selection), `disembark()`, `buyBoat(type, shipwright)` once a tick through the Boat Emporium, deck objects through `interact(name, action)`), `api.login`, `api.breaks`,
  `api.config` and `api.activities` (trusted scripts only).
- Run energy: `api.run.enableIfAbove(n)`, or keep a `new AutoRun("MyScript").range(20, 50)` field
  (`org.pureapps.api.AutoRun`) and call `autoRun.tick(api)` from `onLoop`: it turns run on at a
  random energy in the range, picked again each time run turns off.
- `api.activities` (`org.pureapps.api.builder`, trusted scripts only): the account builder's
  activities. An `Activity` serves `Goal`s (`Goal.level(Skill.FISHING, 35)`,
  `Goal.count("Tempoross", 20)`, `Goal.quest(Quest.RUNE_MYSTERIES)`, `Goal.item("Raw trout", 150)` to own
  items, counted with `goal.remaining(api)`, `Goal.tutorialIsland()` to finish Tutorial Island), `rate(goal)` returns a
  `Rating` (`Rating.of(xpPerHour, setupMinutes, detail)`, `Rating.unavailable(reason)`, or
  `Rating.needs(ratingOnceMet, Goal.level(Skill.PRAYER, 43), ...)` when only levels or quests are
  missing, which the builder may train first as sub-instructions), `options(goal)` can list more
  `Rating.needs(...)` ratings for a faster way the activity can also skip (e.g. food gathered first), and
  `start`/`tick`/`stop`/`isRunning`/`canInterrupt` run it. Attach what a rating would do with
  `rating.with(setup)` (any class implementing `org.pureapps.api.builder.Setup`, often an enum of
  spots): the builder calls `start(goal, chosenRating)`, so read `chosen.setup(MySpot.class)` there
  instead of picking again (`start(goal)` alone is the fallback). Override `progress()` to return a
  number that changes as an activity without xp gets somewhere (e.g. a quest's varp), or the
  builder stops it as stuck after its Stall Minutes. Override `startsAtBank(goal)` to start with the bank left open after that deposit (an activity that withdraws first). Override `keepItems(goal)` to name the items
  (`*` wildcards) the activity uses: whenever a bank is open the builder deposits every other
  inventory item (null, the default, leaves the inventory alone). `upgrades(goal)` can list
  `Upgrade.of(itemId, name, ratingWithIt)` (optionally `.shop(npc, tile, price)`, `.amount(n)` for supplies such
  as food, which arrive noted from the GE, `.perWorld(n)` to buy at most n per world from the shop and hop for the rest, since each one bought raises its price): a better tool or stock of supplies the builder buys first (GE, or shops for ironmen) when the time saved is worth the coins.
  An activity that fights with the player's melee gear returns true from `fights(goal)` and rates
  itself with a piece worn in `rateWithGear(goal, slot, itemId)` (shorter fights, less food): the
  builder then weighs buying better weapons and armour (`GearCandidates`) for it like any upgrade,
  counting what each piece saves every later instruction that fights. A quest script lists every
  fight left in the whole quest in `questFoes(ctx, progress)` (default: `foes`).
  `Brutus` (The Ides of Milk's bull, and the boss in the Lumbridge cow field after it): `Brutus.foe()` for the
  combat model, `isSpecialCue(text)`/`isSlam(text)` for his `*snorts*` (slam) and `*growls*` (charge), and
  `dodgeTile(npc, playerTile, slam)` for the tile to step to; `START_GATES` (Release) and `EXIT_GATES` at `GATE`.
  `rating.also(Skill.PRAYER, xpPerHour)` lists experience the activity also gives another skill on the way;
  the builder credits it against later level instructions in that skill (`LaterGoals.minutesSaved`), and
  `variants(goal)` can list slower ratings of the same activity with more side-gains (each with its own
  setup), e.g. combat that also buries the bones. `Haul.worthTaking(coinValue, ticks)` says whether a
  drop is worth the time to pick up. Register one with
  `api.activities.register(activity)` in `onStart` and unregister it in `onStop`; PureBuilder
  then picks it when it reaches the goal sooner than the others.
  Shared helpers for ratings: `Travel.minutes(from, to)` (walk minutes, underground counted from
  the surface, capped), `Travel.nearestBank(tile, members)`, `Travel.bankTrip(spot, members)`,
  `Travel.bankOnTheWay(from, to, members, maxDetour, usable)` (a bank passed on a walk);
  `CombatTraining.combatStyleFor(skill)` and `CombatTraining.styleFor(skill, weaponStyle)` (the
  attack style that trains a skill); `food.suits(maxHitpoints)` (heals at most half);
  `Cooking.success(cookingLevel, level, stopBurn)`.
  `TrainingNpc.values()` lists the training spots: the hand-tuned constants (`TrainingNpc.COWS`), then about 900
  generated from the wiki (`training.tsv`, members spots too, `isMembers()`, `getQuest()`); `valueOf(name())` finds one again.
  `CombatTraining.estimate(fighter, TrainingNpc.COWS, foodHeal, members)` estimates combat xp per hour,
  hit chance and food per hour on a training monster (`CombatTraining.fighter(api, itemManager, skill, loadout)`), with the wiki's
  hit formulas (`hitChance(attackRoll, defenceRoll)`, `averageHit(maxHit)`; each monster attacks with its own
  `TrainingNpc.getAttackType()`/`getAttackBonus()`, so armour cuts the food it needs);
  `burying(estimate, xpPerBone)` is the same spot while burying each kill's bones (`Bones`, `TrainingNpc.getDropBones()`), and
  `foodFor(estimate, xp)` is the food a goal eats and `xpPerHourWithFood(estimate, xp, owned, fallbackXpPerHour)` the rate once it runs out.
  `lootAndCook(lootEstimate, lootFedEstimate, fedEstimate, xp, owned, load, tripMinutes, meatCookSuccess(cooking))` rates fighting a monster
  whose raw drop cooks into food (`TrainingNpc.getDropFood()`: chickens, cows) until the food covers the rest at a spot that needs it,
  eating the cooked drops there (`lootFedEstimate`, the same monster with the drop's heal; `isEating()`) when that beats resting.
- `api.combat`: `attackStyles()` (the `AttackStyle` of each combat tab button for the wielded
  weapon), `setAttackStyle(AttackStyle.AGGRESSIVE)`, `setAutocast(CombatSpell.FIRE_STRIKE, false)`,
  `combatStyle()` (`CombatStyle` MELEE, RANGED or MAGIC), `eatIfBelow`, `enableSpec`,
  `setAutoRetaliate`, `isCombatGear(itemId)` (equipment with any combat bonus), `canWield(itemId)` (the wield requirements met, from `Weapons`). For a whole setup, `loadout(CombatStyle.RANGED, AttackStyle.RAPID, 200, true)`
  picks a `CombatLoadout` from the inventory, worn items and cached bank (the melee weapon dealing the most damage per tick; pass a target's Defence level and defence bonuses as two more arguments to pick against it, and a map of item id to quantity after them to pick as if those were owned too; ammo it
  fires, or the strongest spell the runes pay for plus a staff; falls back to melee; melee also
  wears the best owned `MeleeArmour` the Defence level allows, `getArmour()`); withdraw its
  `getSupplies()`, then call `attack(npc, loadout)` each time the player isn't fighting (one click
  per call: wield, wear armour and ammo, set autocast or attack style, then attack or cast).
  `Shops` has every shop (owners, tile, stock, `buys(item)`, `sellPrice(value, held, normal)` and `buyPrice`/`buyCost(value, held, normal, amount)` (each one bought raises the next one's price) from the wiki's
  formula; `Shops.nearest(Shops.buying(item, members), here)`; that skips shops with entry requirements,
  `Shops.buying(item, members, client)` keeps those the player can enter, `getRequirements()` lists them). `Drops.forMonster(name, combatLevel)` has the wiki drop
  table of any monster, the version of that level (`chance(members)`, `averageQuantity()`, value); `LootIncome.estimate(...)` rates the coins per hour
  of selling them. `MeleeArmour` lists free-to-play helms, bodies, legs and shields by tier (`forSlot(slot)`,
  `canWear(defence, questDone)`, `getShop()` for the shopkeeper and tile, `getPrice()`).
  `MoneyMaker` lists free-to-play money making methods (inputs and outputs per action, the wiki's
  actions per hour, GE buy limits; trusted scripts can rate one with `MoneyMaking.estimate(maker,
  ItemPrices::price, coins, remaining)`).
  `CombatPrayer` lists the free-to-play combat prayers (level, stat multiplier, drain;
  `best(stat, level)`, `ticksPerPoint(prayers, prayerBonus)`, `getPrayer()` for `api.prayers.activate`), and
  `Altars.nearest(tile, members)` the nearest altar a bot may pray at (`getTile()`, `getLocation()`).
  `Facilities` lists every skilling facility in the map from the game cache (`Kind`: `COOK`,
  `FURNACE`, `ANVIL`, `PRAYER_ALTAR`, `RUNE_ALTAR`, `SPINNING_WHEEL`, `LOOM`, `POTTERY_WHEEL`,
  `POTTERY_OVEN`, `WATER`): `Facilities.nearest(Kind.FURNACE, tile, members, null)` (or a filter such
  as `Facility::isRange`), `usable(kind, members, filter)`; each has `getTile()`, `getObjectId()`, `getName()`.
- `api.mistakes`: the occasional human mistake in place of a click (`MistakeType`: `HESITATE`
  skips it, `MISCLICK_NEAR` walks beside the target, `DOUBLE_CLICK`, `WRONG_NEIGHBOUR`,
  `BANK_MISQUANTITY` withdraws 1). Off unless the user turns it on in the PureApi plugin, or the
  script calls `api.mistakes.configure(new Mistakes.Settings(0.03, EnumSet.of(MistakeType.HESITATE), 20))`
  (reset when the script stops). Wrap actions that must not fail in
  `api.mistakes.suppress(() -> api.inventory.interact("Shark", "Eat"))`. Any `interact` can
  return false or do something else, so check the game next tick; never assume a click landed.
- `api.states`: `priorities(name)` builds a priority selector (`.rule(name, condition, action)`
  in priority order, then `.register()`); `build(name)` builds a state machine. Both are ticked
  every game tick. Stop them in `onStop` with `api.states.stop(name)`.
- `api.quests`: plays a quest written as a `QuestScript` (`org.pureapps.api.quests`):
  `quest()` names RuneLite's `Quest` (a miniquest RuneLite's enum leaves out returns null and
  overrides `id()` with a `QuestId`, e.g. `QuestId.NATURAL_HISTORY_QUIZ`; read a quest's state with
  `state(client)`),
  `progress(client)` reads the quest's varp, `finalProgress()` gives its value once finished (for progress bars), `step(ctx, progress)` returns the next step (build
  them with `QuestSteps.talk`, `interactObject`, `useOnObject`, `pickUp`, `buy`, `dig`, `custom`, ...;
  `pickBush(name, tile, done)` for berry bushes, which keep Pick-from while empty; `waitFor(describe, done)`
  for a step that only waits, e.g. a fire burning down, during which the runner fetches other items),
  `afterFinish(ctx)` returns a step to run once the quest is finished (open a reward crate, drop a
  book), or null when nothing is left; `QuestSteps.bankEverything(name)` banks everything carried and
  worn before a Wilderness trip, `QuestSteps.makeRoom(ctx, slots)` drops junk (or the weakest food)
  until that many slots are free (for an object or NPC that hands over several items),
  untradeable items the account held before the quest started (`ctx.heldBeforeStart(id)`) are banked
  like junk, not kept as quest items,
  NPC, object and ground item steps stop the walk and click once their target is reachable near the step's tile,
  a step implementing `QuestSteps.SelfPaced` is ticked even while the player walks or animates (for
  timed puzzles that click every tick; it must pace its own clicks),
  a step implementing `QuestSteps.IgnoresAttackers` deals with attackers itself: the runner's escape, rest and
  recover rules stay out while it runs (it must leave danger by itself, e.g. grab an item past a guard and walk out),
  a step implementing `QuestSteps.WalkInterrupt` is ticked during a walk while `interruptsWalk(ctx)`
  is true (e.g. a monster to attack showed up; the step cancels the walk with `api.walker.cancel()`),
  `items(ctx, progress)` lists `ItemRequirement`s, which the runner takes from the bank or gets
  through their sources (`.gather(...)`, or `.gather(next, usable)` for a source that runs out such as a quest crate, `.spawns(ItemSpawn.f2p(x, y, plane), ItemSpawn.members(...))` to pick it up from the nearest ground spawn the world allows, `.shop(npc, tile, price)`, `.ge()`); mark an item the
  quest hands out with `.fromQuest()` so the runner leaves it (and items gathered with it) for the
  quest's steps, mark an item that is only nice to have with `.ifBanked()` (withdrawn when the
  bank has it, e.g. toll coins), and
  `dialogueOptions()` lists the options to pick. `onMessage(ctx, message)` sees each game message
  and dialogue screen, for steps the game keeps no var for (it is lost on restart). `transports()`
  returns a `Predicate<Transport>` for transports the quest's walks may use even when the walker
  plugin turns their kind off (e.g. a ship the quest must sail on). Start it on the client thread with
  `api.quests.start(quest, QuestOptions.defaults(), onStop)`; stop it with `api.quests.stop()`.
  `QuestOptions.builder().tickDelayMin(0).tickDelayMax(2)` adds a random extra wait after each
  action (every `ctx.waitTicks(n)`). The runner turns run on by itself once energy reaches a
  random level from `runEnergyMin` to `runEnergyMax` (20-50); `.autoRun(false)` turns that off.
  `.buryRemains(true)` picks up the bones and demonic ashes the quest's fights drop nearby and
  buries or scatters them between fights. `.allowWilderness(false)` never uses item spawns in the
  Wilderness (`ItemSpawn.wilderness()`); the item comes from its other sources.
  To play several in order, pass `new QuestChain(List.of(questA, questB), continueOnFailure)`
  instead; finished quests are skipped and `chain.getFailures()` lists the ones that failed.
  `chain.stateOf(i)` gives each quest's `QuestChain.Entry` (QUEUED, PLAYING, DONE, SKIPPED, FAILED).
  A quest that fights lists its monsters in `foes(ctx, progress)` as `Foe`s with their wiki stats
  (`Foe.of(name, hitpoints, attack, defence, magic).defence(...).attacks(type, bonus, maxHit, speed)`,
  plus `.flinched(share)` for a monster fought by flinching from a safespot, where only that share
  of the damage lands); while `fights(ctx, progress)` holds the runner works out the food those
  fights need from the combat formulas (`ctx.healToBring()`, `ctx.foodSupplies()`), fetches it, eats
  it in fights at `ctx.eatAt()`, and fails the run before the fight when the food owned is short
  (only the best 18 pieces count, as no more are brought). At bank visits the
  runner deposits every tradeable item no requirement lists; `keepItems(ctx, progress)` names more
  items to keep. `wornGear(ctx, progress)` maps equipment slots to items the quest needs worn (an
  anti-dragon shield): the combat loadout wears them there instead of its own pick, so attacking
  never swaps them off.
  `needs(ctx, progress)` lists what the quest can't get itself as account builder `Goal`s (skill
  levels such as `Goal.level(Skill.SMITHING, 34)`, items such as `Goal.item("Coins", 12000)`); the
  builder trains or gathers the unmet ones before starting the quest. `cannotStart(ctx, progress)`
  gives a reason no goal fixes (e.g. too few quest points), or null. A members' quest overrides
  `members()` to return true; `blocked(ctx, progress)` then also refuses it on a free world. A quest
  another quest needs is a need too: `Goal.quest(Quest.X_MARKS_THE_SPOT)`. When the game answers "You
  do not meet all of the requirements to start" the run fails at once.
- `api.graves`: gets items back after a death. `needsRecovery()` is true after a death (or while a
  grave is up, or a death's items were never collected); call `tick()` each tick then, with nothing
  else running: it walks to the grave and loots it, or once it ran out collects the items from Death
  in Death's Office (Lumbridge graveyard or Edgeville entrance) and leaves through the portal.
- `api.invokeLater(runnable)` runs code on the client thread from another thread.
- `api.waits.waitUntil(condition, timeoutMs)` blocks: only use it off the client thread, which
  only trusted scripts can start.

Queries are fluent filters: `api.npcs.search().withName("Banker").nearestReachable()` returns an
`Optional`. `nearest()` is straight-line distance and ignores walls; `nearestReachable()` and
`reachable()` (NPCs, players, objects, ground items) only find things the player can walk to,
except that an NPC, player or object query's `nearestReachable()` falls back to the nearest one on
the player's floor when none can be reached (e.g. behind a closed gate): check
`Reachability.canReach(target)` and walk to it with `api.walker` (which opens doors) instead of
clicking it. The facades' `interact(name, ...)` methods already use `nearestReachable()`. An NPC's
`nearestReachable()` and `Reachability.canReach(npc)` also count one across a counter, bars or a
fence (to talk to a shopkeeper); a melee attack can't reach over those, so pick a monster to fight
with `api.npcs.search().withName("Cow").alive().notFoughtByOthers().nearestWalkable()` and check
`Reachability.canWalkNextTo(target)` before attacking (else walk to it, opening the pen's gate).
`notFoughtByOthers()` skips ones another player is fighting (it targets them, they target it, or it
shows a health bar from someone else's hit).

## Conventions

- Prefer names and `net.runelite.api.gameval` constants (`ItemID`, `NpcID`, `ObjectID`,
  `VarbitID`) over magic numbers.
- Write the main loop as "look at the game, do one thing, return". Don't send the same action
  again every tick while the player is still moving or animating from the last one.
- Keep helper classes in their own files in this folder; they are all compiled together.

## Working with the user

- You can't run the game. The user tests the script in the client and tells you what happened.
  Ask them to paste the Scripter output (compile errors, the script's `log` lines, and the stack
  trace when the script stopped) instead of guessing.
- With "Hot Reload" on, every file you save recompiles and restarts the script, so finish a
  change before saving files that depend on each other, and say when it is ready to test.
- The user may prompt you from the Scripter editor's AI chat instead of a terminal. Each prompt
  then ends with an "Editor context" block: the open file, the cursor line, the selected text
  and the current compile problems. "This" or "here" in the prompt usually means the selection or
  the open file. Hot reload waits until your turn ends, and in review mode the user sees a diff of
  each file you changed and may revert it, so keep changes to what was asked.
- Add `log` lines at decisions (what the script saw and what it chose) so the output explains a
  failure.
- Don't add build files, a `src/` layout or tests; the Scripter compiles this folder as it is.
- `CLAUDE.md` in this folder only imports this file. Edit this file, not that one, if notes
  should change; an edited `AGENTS.md` is no longer updated by the Scripter.
