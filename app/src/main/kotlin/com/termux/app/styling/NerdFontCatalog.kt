package com.termux.app.styling

/**
 * One family from the official Nerd Fonts v3.5.0 manifest and release.
 *
 * Archive names, byte sizes, and SHA-256 values are pinned from the release's official
 * `SHA-256.txt` and GitHub release assets. Metadata is never fetched at runtime.
 */
data class NerdFontCatalogEntry(
    val id: String,
    val family: String,
    val archiveName: String,
    val archiveSha256: String,
    val archiveBytes: Long,
    val isMonospaced: Boolean,
    val bundled: Boolean = false,
    val primaryFontSupported: Boolean = true
) {
    val storageName: String get() = "nerd_$id"
    val archiveUrl: String get() =
        "https://github.com/ryanoasis/nerd-fonts/releases/download/v${NerdFontCatalog.VERSION}/$archiveName"
}

/**
 * Complete pinned Nerd Fonts v3.5.0 family catalog.
 *
 * Generated from:
 * - https://raw.githubusercontent.com/ryanoasis/nerd-fonts/v3.5.0/bin/scripts/lib/fonts.json
 * - https://github.com/ryanoasis/nerd-fonts/releases/download/v3.5.0/SHA-256.txt
 * - https://api.github.com/repos/ryanoasis/nerd-fonts/releases/tags/v3.5.0
 *
 * Fira Code, Hack, and JetBrains Mono are bundled by this app and excluded from [optionalEntries].
 * Symbols is listed, but disabled because it cannot serve as a terminal primary text font.
 */
object NerdFontCatalog {
    const val VERSION = "3.5.0"
    const val RELEASE_URL = "https://github.com/ryanoasis/nerd-fonts/releases/tag/v3.5.0"
    const val DOWNLOADS_URL = "https://www.nerdfonts.com/font-downloads"
    const val LICENSES_URL = "https://github.com/ryanoasis/nerd-fonts/blob/v3.5.0/license-audit.md"

    val entries: List<NerdFontCatalogEntry> = listOf(
        entry("0xproto", "0xProto", "0xProto.zip", "96044c9b041dbe6341a2e8b831259ba8e60f4646e55b721b5f6577505381df1f", 12135004L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("3270", "3270", "3270.zip", "9a3248181a3ec4f07c1255a167b65c3a596ff3f383e76dec95473a24684edf8e", 13717584L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("adwaitamono", "AdwaitaMono", "AdwaitaMono.zip", "0e851f9149b77ba3d9a1b5a6c8af981016f45d71bfa25ea49ad70469e6956bdd", 22997247L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("agave", "Agave", "Agave.zip", "45db201218af202c952438bb319076bad797ffc0f551d4dea87ab18628dd0b74", 8721965L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("annotationmono", "AnnotationM", "AnnotationMono.zip", "892d96f2c9213276bfb92e937a6616ac88c53eb5d80823e7124350655db44b1d", 61797350L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("anonymouspro", "AnonymicePro", "AnonymousPro.zip", "df098db36735b22b3b83c8eb325e1268cedc2b98c781f1e144df6cf597e12a64", 17239175L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("arimo", "Arimo", "Arimo.zip", "fa6679f4a0b34f88f02b15b41a9f050f220af91a7868774a32f53ac32120864f", 12938833L, isMonospaced = false, bundled = false, primaryFontSupported = true),
        entry("atkinsonhyperlegiblemono", "AtkynsonMono", "AtkinsonHyperlegibleMono.zip", "a7d63d94170da9349601e6d70800d4c500bc0667cbbe095b94266d5e25483e40", 34835082L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("aurulentsansmono", "AurulentSansM", "AurulentSansMono.zip", "f82d15c4bafed12372b67dfa016fbe140241e2405297dd434e78d6642b9f1cd1", 4321578L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("bigblueterminal", "BigBlueTerm", "BigBlueTerminal.zip", "d0d806d60c52fe54aabed1750800f7dee5876bb88b5cc7d0d94fc0592f0cdf38", 7897900L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("bitstreamverasansmono", "BitstromWera", "BitstreamVeraSansMono.zip", "62c7428b355bb4df234780c1ef4f3100f815f322245205951b54f6d4d91b7cf1", 16879486L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("ibmplexmono", "BlexMono", "IBMPlexMono.zip", "9fe022730c8245b37c8c5f9c6ae5b1f8fbd1e2e395fcb75f047c14dcbc8da653", 63637056L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("cascadiacode", "CaskaydiaCove", "CascadiaCode.zip", "34230d1534c70976bc508abfa9a3b0ec3faf12881e83b85eb5a0cbe225682256", 55500625L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("cascadiamono", "CaskaydiaMono", "CascadiaMono.zip", "b31fafd248c001ffa4c7149ced737bf5e3aea0e381ee44755ad32919429a5af2", 55155693L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("codenewroman", "CodeNewRoman", "CodeNewRoman.zip", "426464ab70a2bcf0265b7a06344ab69d34bb261c036fb98a9e9045917c0a7282", 25491719L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("comicshannsmono", "ComicShannsMono", "ComicShannsMono.zip", "d3c6897700494181ea0184acf3d0caa0dc8a8bf2826316e29402115ffe844ffe", 9009612L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("commitmono", "CommitMono", "CommitMono.zip", "e45728b8d5530956c75594bfb625b41c54109b63ee0995a5ea5dc996675de2ef", 17683346L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("cousine", "Cousine", "Cousine.zip", "ac70139a1baf9907169ee5eb78729e6a8ea1fbe3e9aadd6f30a4ebe4894bed63", 18455481L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("d2coding", "D2KodingLigature", "D2Coding.zip", "576b27f898420acadb0ba1782063b9fa2b1388db6567e6b1ea753e01e5842868", 20699788L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("daddytimemono", "DaddyTimeMono", "DaddyTimeMono.zip", "4c38225e92d966511351af19df80b2da68e5a962193b7526d3820fa5cc5d2e55", 4122245L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("dejavusansmono", "DejaVuSansM", "DejaVuSansMono.zip", "e3178e5952d4e743ea83a68f9fcdebb7645cadfd32cc094e06b07b836ec6ae11", 18600326L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("departuremono", "DepartureMono", "DepartureMono.zip", "dcf6f77d5f75c04793e0e058a28dd001088adffa26e9bb9cd2d263c6c1a69acb", 3991548L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("droidsansmono", "DroidSansM", "DroidSansMono.zip", "d9b6c0b49bf1291135e0a844b5d62dda28f4c37ffca08d0adf7d20f94ce30f01", 4833818L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("envycoder", "EnvyCodeR", "EnvyCodeR.zip", "229303d28335571ca54e04891d4305f6069ae14d4447464eea9f9a701fd72277", 12645504L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("fantasquesansmono", "FantasqueSansM", "FantasqueSansMono.zip", "b5e65ad983db8d12f512c0b89ce4a4c551491b65722f4268182d5e79d9864891", 17522824L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("fira_code", "FiraCode", "FiraCode.zip", "8ad2834d8ea1945d8ab042538e608f6370573a29913aa94b5e6bbc92ffacbab5", 28061509L, isMonospaced = true, bundled = true, primaryFontSupported = true),
        entry("firamono", "FiraMono", "FiraMono.zip", "de42b4d0e1dad02ba957b7176f457227e1a27ce98cf2026528e61cdcb9a27c9b", 13479319L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("geistmono", "GeistMono", "GeistMono.zip", "923a27caf8a588060dfff04ddec248de928a4f7ca6003c64377726177b734946", 80190002L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("go_mono", "GoMono", "Go-Mono.zip", "3051f7918e54fb990b86beddc1efd2268ea9f72c2c9993b2cea3ba78d6685bd2", 17471967L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("gohu", "GohuFont", "Gohu.zip", "5337faa50aa74be65cb2e34c5a8d8651f75c34af51b2589a5ab2dc82425accc4", 16809142L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("googlesanscode", "GoogleSansCode", "GoogleSansCode.zip", "524485e914b365b04c8e443773e43cbc43786f64b44131f0efe26166db12b1af", 51480771L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("hack", "Hack", "Hack.zip", "24a54aa41ff8ca5829409bfeb1bc2883b9fcafbf79f8d4b7674898550cb5e3b3", 18329745L, isMonospaced = true, bundled = true, primaryFontSupported = true),
        entry("hasklig", "Hasklug", "Hasklig.zip", "59f5152d6741507a62367867108b2e91bdb04233534a2a221e349957ccb00c3e", 63139337L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("heavydata", "HeavyData", "HeavyData.zip", "43e7143b84bec3c07c3ad75c37c564b4e0e73751d03fc0ca3b88bb9ea2ea7d7d", 2696889L, isMonospaced = false, bundled = false, primaryFontSupported = true),
        entry("hermit", "Hurmit", "Hermit.zip", "eea1b24c622f915bc9900173964f0efbf863c0df94812803d3bbb42b6676ef1a", 26038173L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("ia_writer", "iMWriting", "iA-Writer.zip", "257ac8b202f55b55bc4c0478141bbda191f7866001b3701a052715bfef7be5a0", 36634715L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("inconsolata", "Inconsolata", "Inconsolata.zip", "2b10ce776b163467d64b20fc64a2d5b83cd79d596c3acc0ab9cec7adea5c2e8f", 7886661L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("inconsolatago", "InconsolataGo", "InconsolataGo.zip", "f4b667da44873d1d729a8cb15ca92080b9a88ca42cd8ae76482dba9382b4fdce", 8139684L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("inconsolatalgc", "Inconsolata LGC", "InconsolataLGC.zip", "82eb4494c7dd073a53b2530c54e782df13be67ad0c0479f6c378444568f1645d", 16578217L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("intelonemono", "IntoneMono", "IntelOneMono.zip", "c2d1f4af8b1fb3ff204ef6cb5ef5af8c26fb8d4fe60bb83ff110fb752f14532c", 31668243L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("iosevka", "Iosevka", "Iosevka.zip", "a5a218f974c7d3264c0f330c514364aacabf839d8ee92abd05f5c2cd4ad514b5", 400798979L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("iosevkaterm", "IosevkaTerm", "IosevkaTerm.zip", "378114a305c94a422548a0056f8ff8976043a0531f985bebe0be74772fe26081", 400262724L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("iosevkatermslab", "IosevkaTermSlab", "IosevkaTermSlab.zip", "9643c8e65550e56c1e5ba62e57d6afefd42a348e9876a96854b20954488a8e77", 225121851L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("jetbrains_mono", "JetBrainsMono", "JetBrainsMono.zip", "9577de1ae84ec523df16fc69bac5338b89497a5b4fb91489e2dcb79dc06ac2b5", 131540280L, isMonospaced = true, bundled = true, primaryFontSupported = true),
        entry("lekton", "Lekton", "Lekton.zip", "41358a62e4762c229050ae0d4f7d1a579e2bb2b2dbe284845e25f39590322b22", 11630654L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("liberationmono", "LiterationMono", "LiberationMono.zip", "4466e29a9f2c9ef9c8e8d74f1f755b0aca47c42506b31530cf6744ef4bd01480", 43307706L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("lilex", "Lilex", "Lilex.zip", "bacb5017763621c8c9e1155cd7c7aa7e64b5b25ddcf97f50fbae227dcd490cbf", 40690520L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("martianmono", "MartianMono", "MartianMono.zip", "acc4cfc26d4d4934ec0c0239bf456469768dc114174a0d38660a4e6c210a3b75", 23860662L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("meslo", "MesloLG", "Meslo.zip", "6ef538a04f30af9cbe4d95fbd1ae31205a04c48a2c09714f6145ac9cbb6d1b64", 114191394L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("monaspace", "Monaspice", "Monaspace.zip", "30f8be0241f15616a0a0222334fd55b7f5de02158f47181d63e6a4ec5389bc0e", 269346647L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("monofur", "Monofur", "Monofur.zip", "1f9ff2a43274d21509320148a7545d6fb8954f271143d85c348d7a5a7c260cbe", 13469969L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("monoid", "Monoid", "Monoid.zip", "79b1b1875491d3d86924dd765f1d1aa47b87f1124a0836d2c824124bdb6aad7a", 16458480L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("mononoki", "Mononoki", "Mononoki.zip", "ddc51a618685578c78b5ad431fa592475a46430257007c9f8be4cf7904db2e27", 15726674L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("mplus", "M+", "MPlus.zip", "e53ca61293ea3d807247553d17abc9c05c075c2500bdd5ef6f14fab81b60fe08", 186007631L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("noto", "Noto", "Noto.zip", "11e6dba0bb1a11f56774f77e7e0eb5b23cc779012163688c4362b57820c9c9af", 609904312L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("opendyslexic", "OpenDyslexic", "OpenDyslexic.zip", "e869dbe20e6159e270cba0b44709d10f6755fb96ebcca28f23aa03e3423a4759", 27545509L, isMonospaced = false, bundled = false, primaryFontSupported = true),
        entry("overpass", "Overpass", "Overpass.zip", "8e8474b73ebad7e2924308ee8a65471b396b50c1a6da8fc3044a04300ad4b9a9", 63930568L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("profont", "ProFont", "ProFont.zip", "40772cac1c1992b755ad6612a673a2e365cf0244f67d4055bb7d6a2c1fe54255", 7812749L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("proggyclean", "ProggyClean", "ProggyClean.zip", "96dc70a844481f0758ba223e18a9f19907235d7575e38f73cea842ab832b99d8", 12980708L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("recursive", "RecMono", "Recursive.zip", "564d2c44e5444742a27a579779e43c20f7447be84a442ab65ec42bd46077444e", 68834139L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("robotomono", "RobotoMono", "RobotoMono.zip", "31672eccf247e70e220466e65ee9dd9ff78bf1af264fdb9631d5702ea60b44fa", 51624647L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("sharetechmono", "ShureTechMono", "ShareTechMono.zip", "8a638e0b6a35b5011c1ddc8ac8196637b5ab6b101071f4764c6dca171fe87622", 3843305L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("sourcecodepro", "SauceCodePro", "SourceCodePro.zip", "e8d18dae2086b5f45dc6e20c10c9b35c52d1bfeaf50426cfb54002fb744f0fa0", 56612576L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("spacemono", "SpaceMono", "SpaceMono.zip", "5186068baed57a2687e64c9994a3831dfac00e370d2fedc6186a856c1d54c88a", 15788193L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("nerdfontssymbolsonly", "Symbols", "NerdFontsSymbolsOnly.zip", "49362450cd61b32c7d1dadbb98e82696d77cc215344636d25eabc8a82d6f8d7f", 2992964L, isMonospaced = true, bundled = false, primaryFontSupported = false),
        entry("terminus", "Terminess", "Terminus.zip", "b8afb1c8d7bbf7845f60af886183479b20fb44f1efa48ef52a99e82cf1135bd4", 16598188L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("tinos", "Tinos", "Tinos.zip", "e74f7112ac435ab758006b282053be928ea255feb52aa625dc9a64d6b2fbf954", 12508267L, isMonospaced = false, bundled = false, primaryFontSupported = true),
        entry("ubuntu", "Ubuntu", "Ubuntu.zip", "eeb1999a9d575e91055a53156b039dded874d6f3955ff13b9b1b9ac5259c4abb", 24978583L, isMonospaced = false, bundled = false, primaryFontSupported = true),
        entry("ubuntumono", "UbuntuMono", "UbuntuMono.zip", "851295f0c8adb0b4fcbaf5e5d81629192136bab42dda2d224910dabc69769aed", 16311259L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("ubuntusans", "UbuntuSans", "UbuntuSans.zip", "f349536ccc2663e411a9db24762f16f30a785159f5fd1f3c24108219cc648557", 54970695L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("victormono", "VictorMono", "VictorMono.zip", "6f81fe83b7d4fcb27941d13e860c43e36621e124a330f5aeff2adcd8dede57b4", 88363275L, isMonospaced = true, bundled = false, primaryFontSupported = true),
        entry("zedmono", "ZedMono", "ZedMono.zip", "b20489b8f17e6ffb8f83e385958c4353ad43e392ae8c3b689e6002006c42cc57", 276715537L, isMonospaced = true, bundled = false, primaryFontSupported = true)
    )

    val optionalEntries: List<NerdFontCatalogEntry> = entries.filterNot { it.bundled }

    fun find(id: String): NerdFontCatalogEntry? = optionalEntries.firstOrNull { it.id == id }

    private fun entry(
        id: String,
        family: String,
        archiveName: String,
        archiveSha256: String,
        archiveBytes: Long,
        isMonospaced: Boolean,
        bundled: Boolean,
        primaryFontSupported: Boolean
    ) = NerdFontCatalogEntry(
        id = id,
        family = family,
        archiveName = archiveName,
        archiveSha256 = archiveSha256,
        archiveBytes = archiveBytes,
        isMonospaced = isMonospaced,
        bundled = bundled,
        primaryFontSupported = primaryFontSupported
    )
}
