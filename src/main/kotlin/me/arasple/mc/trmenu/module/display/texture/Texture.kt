package me.arasple.mc.trmenu.module.display.texture

import me.arasple.mc.trmenu.api.menu.ITexture
import me.arasple.mc.trmenu.module.display.MenuSession
import me.arasple.mc.trmenu.module.internal.hook.HookPlugin
import me.arasple.mc.trmenu.module.internal.item.ItemRepository
import me.arasple.mc.trmenu.module.internal.item.ItemSource
import me.arasple.mc.trmenu.util.Regexs
import me.arasple.mc.trmenu.util.bukkit.Heads
import me.arasple.mc.trmenu.util.bukkit.ItemHelper
import org.bukkit.DyeColor
import org.bukkit.Material
import org.bukkit.block.banner.Pattern
import org.bukkit.block.banner.PatternType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BannerMeta
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.util.NumberConversions
import taboolib.common.reflect.Reflex.Companion.invokeMethod
import taboolib.common.util.Strings.similarDegree
import taboolib.library.xseries.XMaterial
import taboolib.module.kether.isInt
import taboolib.module.nms.MinecraftVersion
import taboolib.platform.util.buildItem
import java.util.*

/**
 * @author Arasple
 * @date 2021/1/24 11:50
 */
class Texture(
    val raw: String,
    val type: TextureType,
    val texture: String,
    val dynamic: Boolean,
    var static: ItemStack?,
    val meta: Map<TextureMeta, String>
) : ITexture {

    override fun generate(session: MenuSession): ItemStack {
        if (static != null) return static!!
        val temp = if (dynamic) session.parse(texture) else texture

        var itemStack = when (type) {
            TextureType.NORMAL -> parseMaterial(temp)
            TextureType.HEAD -> Heads.getHead(temp)
            TextureType.REPO -> ItemRepository.getItem(temp)
            TextureType.SOURCE -> ItemSource.fromSource(session, texture)
            TextureType.RAW -> ItemHelper.fromJson(temp)
        }

        if (itemStack != null) {
            itemStack = buildItem(itemStack) {
                meta.forEach { (meta, metaValue) ->
                    val value = session.parse(metaValue)
                    when (meta) {
                        TextureMeta.DATA_VALUE -> damage = value.toIntOrNull() ?: 0
                        TextureMeta.MODEL_DATA -> customModelData = value.toInt()
                        TextureMeta.LEATHER_DYE -> color = ItemHelper.serializeColor(value)
                        TextureMeta.BANNER_PATTERN -> {
                            patterns.clear()
                            patterns.addAll(value.split(",").let {
                                val patterns = mutableListOf<Pattern>()
                                it.forEach {
                                    val type = it.split(" ")
                                    if (type.size == 1) {
                                        finishing = {
                                            try {
                                                (it.itemMeta as? BannerMeta)?.baseColor = DyeColor.valueOf(type[0].uppercase())
                                            } catch (e: Exception) {
                                                (it.itemMeta as? BannerMeta)?.baseColor = DyeColor.BLACK
                                            }
                                        }
                                    } else if (type.size == 2) {
                                        try {
                                            patterns.add(Pattern(
                                                DyeColor.valueOf(type[0].uppercase()), PatternType.valueOf(
                                                    type[1].uppercase()
                                                )
                                            ))
                                        } catch (e: Exception) {
                                            patterns.add(Pattern(DyeColor.BLACK, PatternType.BASE))
                                        }
                                    }
                                }
                                patterns
                            })
                        }
                    }
                }

            }
            if (type == TextureType.NORMAL && !dynamic) {
                static = itemStack
            }
        }

        return itemStack ?: FALL_BACK
    }

    override fun toString(): String {
        return "{type=$type, texture=$texture, dynamic=$dynamic, static=$static, meta=$meta}"
    }

    companion object {

        val FALL_BACK = ItemStack(Material.BEDROCK)

        fun createTexture(itemStack: ItemStack): String {
            val material = itemStack.type.name.lowercase().replace("_", " ")
            val itemMeta = itemStack.itemMeta

            // Head Meta
            if (itemMeta is SkullMeta) {
                val hdb =
                    if (HookPlugin.getHeadDatabase().isHooked) {
                        HookPlugin.getHeadDatabase().getId(itemStack)
                    } else ""

                return when {
                    hdb != null -> "source:HDB:$hdb"
                    itemMeta.hasOwner() -> "head:${itemMeta.owningPlayer?.name}"
                    else -> "head:${Heads.seekTexture(itemStack)}"
                }
            }
            // Model Data
            if (MinecraftVersion.majorLegacy >= 11400 && itemMeta != null && itemMeta.hasCustomModelData()) {
                return "$material{model-data:${itemMeta.customModelData}}"
            }
            // Leather
            if (itemMeta is LeatherArmorMeta) {
                return "$material{dye:${ItemHelper.deserializeColor(itemMeta.color)}}"
            }
            // Banner

            return material
        }

        /**
         * Create a texture from string
         */
        fun createTexture(raw: String): Texture {
            var type = TextureType.NORMAL
            var static: ItemStack? = null
            var texture = raw
            val meta = mutableMapOf<TextureMeta, String>()

            TextureMeta.values().forEach {
                it.regex.find(raw)?.groupValues?.get(1)?.also { value ->
                    meta[it] = value
                    texture = texture.replace(it.regex, "")
                }
            }

            TextureType.values().filter { it.group != -1 }.forEach {
                it.regex.find(texture)?.groupValues?.get(it.group)?.also { value ->
                    type = it
                    texture = value
                }
            }

            val dynamic = Regexs.containsPlaceholder(texture)
            if (type == TextureType.NORMAL) {
                if (Regexs.JSON_TEXTURE.find(texture) != null) {
                    type = TextureType.RAW
                    if (!dynamic) static = ItemHelper.fromJson(texture)!!
                }
            }
            return Texture(raw, type, texture, dynamic, static, meta)
        }

        private fun parseMaterial(material: String): ItemStack {
            val split = material.split(":", limit = 2)
            val data = split.getOrNull(1)?.toIntOrNull() ?: 0
            val id = split[0].toIntOrNull() ?: split[0].uppercase().replace("[ _]".toRegex(), "_")

            return buildItem(XMaterial.matchXMaterial(FALL_BACK)) {
                var rawMaterial = id

                if (id is Int) {
                    try {
                        this.material = Material::class.java.invokeMethod<Material>("getMaterial", id.toInt(), fixed = true)!!
                        this.damage = data
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        XMaterial.matchXMaterial(id, -1).let {
                            if (it.isPresent) {
                                setMaterial(it.get())
                                this.damage = data
                            } else {
                                XMaterial.STONE
                            }
                        }
                    }
/*                    XMaterial.matchXMaterial(id, (-1).toByte()).let {
                        if (it.isPresent) {
                            setMaterial(it.get())
                            this.damage = data
                        } else {
                            XMaterial.STONE
                        }
                    }*/
                } else {
                    val name = id.toString()
                    try {
                        this.material = Material.getMaterial(name)!!
                    } catch (e: Throwable) {
                        val xMaterial =
                            XMaterial.values().find { it.name.equals(name, true) }
                                ?: XMaterial.values()
                                    .find { it -> it.legacy.any { it == name } }
                                ?: XMaterial.values()
                                    .maxByOrNull { similarDegree(name, it.name) }
                        return xMaterial?.parseItem() ?: FALL_BACK
                    }
                }
            }
        }

    }

}