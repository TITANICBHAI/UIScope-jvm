{pkgs}: {
  deps = [
    pkgs.glib
    pkgs.zlib
    pkgs.xorg.libXi
    pkgs.xorg.libXtst
    pkgs.xorg.libXrender
    pkgs.xorg.libXext
    pkgs.xorg.libX11
    pkgs.libGL
    pkgs.dejavu_fonts
    pkgs.liberation_ttf
    pkgs.freetype
    pkgs.fontconfig
    pkgs.gradle
  ];
}
