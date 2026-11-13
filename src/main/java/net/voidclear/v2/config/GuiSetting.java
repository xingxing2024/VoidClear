package net.voidclear.v2.config;

import org.bukkit.configuration.file.YamlConfiguration;

/** Only these ordinary settings can be changed from the management panel. */
public enum GuiSetting {
    ADAPTIVE("自适应批量","sweep.adaptive.enable",true),
    ENCHANTED("附魔物品保护","drop.protection.enchanted",false),
    CONTAINERS("非空容器保护","drop.protection.nonempty-containers",true),
    DROP_AGE("新掉落保护秒数","drop.protect-seconds",60,0,30,60,120,300),
    SLICE("每片最多实体数","sweep.slice.entities",128,32,64,128,256),
    ACTION_BAR("清扫 ActionBar","actionbar.sweep.enable",true);

    private final String label, path;
    private final boolean fallback;
    private final int number;
    private final int[] choices;
    GuiSetting(String label,String path,boolean fallback) {
        this.label=label;this.path=path;this.fallback=fallback;number=0;choices=new int[0];
    }
    GuiSetting(String label,String path,int number,int... choices) {
        this.label=label;this.path=path;this.number=number;this.choices=choices;fallback=false;
    }
    public String label() { return label; }
    public String value(Settings settings) {
        return choices.length==0 ? (settings.rawBool(path,fallback)?"开启":"关闭") : String.valueOf(settings.rawInt(path,number));
    }
    public void apply(YamlConfiguration config) {
        if(choices.length==0) {config.set(path,!config.getBoolean(path,fallback));return;}
        int current=config.getInt(path,number),next=choices[0];
        for(int i=0;i<choices.length;i++)if(choices[i]==current){next=choices[(i+1)%choices.length];break;}
        config.set(path,next);
    }
}
