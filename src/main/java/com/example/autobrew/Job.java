package com.example.autobrew;

import net.minecraft.item.Item;

import java.util.List;

/** Satu pekerjaan dalam antrean. Tidak berubah setelah dibuat. */
final class Job {
    final String recipeId;
    final AutoBrewConfig.Mode mode;
    final PotionRecipe.Modifier modifier;
    final PotionRecipe.Form form, sourceForm;
    final AutoBrewConfig.Base base;
    final int cycles;                 // 0 = tanpa batas

    private final PotionRecipe recipe;
    private final List<Item> stages;
    private final List<PotionRecipe.PState> states;
    private final int startIndex;     // 0 = mulai dari Water Bottle, 1 = mulai dari Awkward Potion

    Job(String recipeId, AutoBrewConfig.Mode mode, PotionRecipe.Modifier modifier, PotionRecipe.Form form,
        PotionRecipe.Form sourceForm, AutoBrewConfig.Base base, int cycles) {
        PotionRecipe r = null;
        for (PotionRecipe x : PotionRecipe.ALL) if (x.id().equals(recipeId)) r = x;

        PotionRecipe.Modifier m = modifier;
        if (r != null && !r.supports(m)) m = PotionRecipe.Modifier.NONE;
        PotionRecipe.Form f = form;
        if (mode == AutoBrewConfig.Mode.UPGRADE && f.ordinal() < sourceForm.ordinal()) f = sourceForm;

        List<Item> st; List<PotionRecipe.PState> ss; int start = 0;
        if (r == null) {
            st = List.of(); ss = List.of();
        } else if (mode == AutoBrewConfig.Mode.SCRATCH) {
            st = r.build(m, f);
            ss = r.statesScratch(m, f);
            if (base == AutoBrewConfig.Base.AWKWARD && !r.chain().isEmpty()
                    && r.chain().get(0).equals("awkward") && ss.size() > 1) start = 1;
        } else {
            st = r.buildUpgrade(m, sourceForm, f);
            ss = r.statesUpgrade(m, sourceForm, f);
        }

        this.recipeId = recipeId == null ? "" : recipeId;
        this.mode = mode;
        this.modifier = m;
        this.form = f;
        this.sourceForm = sourceForm;
        this.base = base;
        this.cycles = Math.max(0, cycles);
        this.recipe = r;
        this.stages = st;
        this.states = ss;
        this.startIndex = start;
    }

    static Job fromConfig() {
        return new Job(AutoBrewConfig.recipeId, AutoBrewConfig.mode, AutoBrewConfig.modifier,
                AutoBrewConfig.form, AutoBrewConfig.sourceForm, AutoBrewConfig.base, AutoBrewConfig.maxCycles);
    }

    static Job fromData(AutoBrewConfig.JobData d) {
        return new Job(d.recipeId,
                AutoBrewConfig.parse(AutoBrewConfig.Mode.class, d.mode, AutoBrewConfig.Mode.SCRATCH),
                AutoBrewConfig.parse(PotionRecipe.Modifier.class, d.modifier, PotionRecipe.Modifier.NONE),
                AutoBrewConfig.parse(PotionRecipe.Form.class, d.form, PotionRecipe.Form.NORMAL),
                AutoBrewConfig.parse(PotionRecipe.Form.class, d.sourceForm, PotionRecipe.Form.NORMAL),
                AutoBrewConfig.parse(AutoBrewConfig.Base.class, d.base, AutoBrewConfig.Base.WATER),
                d.cycles);
    }

    AutoBrewConfig.JobData toData() {
        AutoBrewConfig.JobData d = new AutoBrewConfig.JobData();
        d.recipeId = recipeId; d.mode = mode.name(); d.modifier = modifier.name();
        d.form = form.name(); d.sourceForm = sourceForm.name(); d.base = base.name(); d.cycles = cycles;
        return d;
    }

    PotionRecipe recipe() { return recipe; }
    List<Item> stages() { return stages; }
    List<PotionRecipe.PState> states() { return states; }
    int startIndex() { return startIndex; }
    PotionRecipe.PState startState() { return states.get(startIndex); }
    boolean valid() { return recipe != null && !stages.isEmpty(); }
    String name() { return recipe == null ? "?" : recipe.resultName(modifier, form); }

    /** Identitas tanpa jumlah siklus (dipakai untuk mencocokkan progres tersimpan). */
    String key() {
        return recipeId + "|" + mode + "|" + modifier + "|" + form + "|" + sourceForm + "|" + base;
    }
}
