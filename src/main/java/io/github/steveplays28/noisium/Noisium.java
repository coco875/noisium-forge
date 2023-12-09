package io.github.steveplays28.noisium;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(Noisium.MODID)
public class Noisium
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "noisium";
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // name of the mod
    public static final String MOD_NAME = "Noisium";

    public Noisium()
    {
        LOGGER.info("Loading {}.", MOD_NAME);
    }
}
