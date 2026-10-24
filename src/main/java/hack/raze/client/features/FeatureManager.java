package hack.raze.client.features;


import hack.raze.client.Raze;
import hack.raze.client.event.EventManager;
import hack.raze.client.features.impl.combat.AimAssist;
import hack.raze.client.features.impl.combat.AnchorMacro;
import hack.raze.client.features.impl.combat.AutoMace;
import hack.raze.client.features.impl.combat.AutoCrystal;
import hack.raze.client.features.impl.combat.Backtrack;
import hack.raze.client.features.impl.blatant.AutoAnchor;
import hack.raze.client.features.impl.blatant.CrystalAura;
import hack.raze.client.features.impl.combat.SpearLungeModule;
import hack.raze.client.features.impl.combat.TriggerBot;
import hack.raze.client.features.impl.blatant.KillAura;
import hack.raze.client.features.impl.combat.MaceSwap;
import hack.raze.client.features.impl.combat.ShieldStun;
import hack.raze.client.features.impl.movement.StrafeFix;
import hack.raze.client.features.impl.misc.AutoTotem;
import hack.raze.client.features.impl.misc.ClickGUI;
import hack.raze.client.features.impl.utility.HoverTotem;
import hack.raze.client.features.impl.utility.JumpReset;
import lombok.Getter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Getter
public class FeatureManager {
    private final CopyOnWriteArrayList<Feature> features = new CopyOnWriteArrayList<>();
    private final Map<Class<? extends Feature>, Feature> featureCache = new ConcurrentHashMap<>();
    private final Map<Category, List<Feature>> categoryCache = new ConcurrentHashMap<>();
    private volatile boolean destroyed = false;

    public void initialize() {
        //? if !auth {
        //? if debug
        //LOGGER.info("If you crash here it is probably a mismatch in server features.json");
         //?}

        // Internal
        //? if !auth {
        features.add(new ClickGUI());
        features.add(new HoverTotem());
        features.add(new JumpReset());
        features.add(new AutoTotem());
        features.add(new MaceSwap());
        features.add(new AutoMace());
        features.add(new ShieldStun());
        features.add(new AimAssist());
        features.add(new CrystalAura());
        features.add(new AutoCrystal());
        features.add(new AnchorMacro());
        features.add(new AutoAnchor());
        features.add(new SpearLungeModule());
        features.add(new TriggerBot());
        features.add(new Backtrack());
        features.add(new KillAura());
        features.add(new StrafeFix());

        features.forEach(Feature::initSettings);
        features.sort((a, b) -> compareCharSequenceIgnoreCase(a.getName(), b.getName()));
        //?}

        // Build cache for O(1) lookups
        // If we don't do this FPS will drop by 50%+ when native'd
        for (Feature feature : features) {
            featureCache.put(feature.getClass(), feature);
        }
        
        for (Category category : Category.values()) {
            categoryCache.put(category, new ArrayList<>());
        }
        for (Feature feature : features) {
            categoryCache.get(feature.getCategory()).add(feature);
        }
        for (Category category : Category.values()) {
            categoryCache.put(category, Collections.unmodifiableList(categoryCache.get(category)));
        }
    }

    private static int compareCharSequenceIgnoreCase(CharSequence a, CharSequence b) {
        int len1 = a.length();
        int len2 = b.length();
        int lim = Math.min(len1, len2);
        for (int i = 0; i < lim; i++) {
            char c1 = Character.toLowerCase(a.charAt(i));
            char c2 = Character.toLowerCase(b.charAt(i));
            if (c1 != c2) {
                return c1 - c2;
            }
        }
        return len1 - len2;
    }

    @SuppressWarnings("unchecked")
    public <T extends Feature> T getFeatureByClass(Class<T> featureClass) {
        if (destroyed) return null;
        Feature feature = featureCache.get(featureClass);
        return feature != null ? (T) feature : null;
    }

    public List<Feature> getFeaturesByCategory(Category category) {
        if (destroyed) return Collections.emptyList();
        return categoryCache.getOrDefault(category, Collections.emptyList());
    }

    public static List<Feature> safeFeatures() {
        FeatureManager fm = Raze.featureManager;
        if (fm == null || fm.destroyed) return Collections.emptyList();
        return Collections.unmodifiableList(fm.features);
    }

    public static <T extends Feature> Optional<T> safeGetFeature(Class<T> featureClass) {
        FeatureManager fm = Raze.featureManager;
        if (fm == null || fm.destroyed) return Optional.empty();
        return Optional.ofNullable(fm.getFeatureByClass(featureClass));
    }

    public void shutdown(Feature skipFeature) {
        destroyed = true;
        for (Feature feature : features) {
            if (feature == null || feature == skipFeature) continue;
            try {
                feature.setEnabled(false);
            } catch (Exception ignored) {
            }
            EventManager.unregister(feature);
            feature.settings.clear();
        }
        features.clear();
        featureCache.clear();
        categoryCache.clear();
    }

    //? if auth {
    /*public void addFeatures(CopyOnWriteArrayList<Feature> features) {
        this.features.addAll(features);
        for (Feature feature : features) {
            featureCache.put(feature.getClass(), feature);
        }
    }

    public CopyOnWriteArrayList<Feature> sort(CopyOnWriteArrayList<Feature> features) {
        features.sort((a, b) -> compareCharSequenceIgnoreCase(a.getName(), b.getName()));
        return features;
    }

    public void addCategoryCache(Map<Category, List<Feature>> categoryCache) {
        this.categoryCache.putAll(categoryCache);
    }
    *///?}
}
