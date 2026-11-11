# Third-party source

The DS build embeds [libretro/melonDS](https://github.com/libretro/melonDS), pinned at `66b5d2634cd0a79030562811e6e05f5532f800ba` in `third_party/melonDS`.

melonDS is copyright Arisotura and its contributors, licensed under GNU GPL version 3 or later. The Libretro frontend, in-tree libretro-common, FreeBIOS and other upstream components retain their individual copyright and license notices. Their complete source and notices remain in the pinned submodule. No proprietary Nintendo BIOS, firmware, artwork or games are included.

Pocket Console's combined DS build is provided under GPLv3-or-later; see LICENSE. Source for this private development build is in this repository, including the exact submodule revision and Android build recipe. Before distributing a build to anyone without repository access, provide its corresponding source and notices together with it.

Original native smoke-test homebrew is generated from source under `tests/native`; it contains no Nintendo logo or other console firmware assets.
