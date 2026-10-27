#version 330
// picom window shader that pixelates every window (picom v10+, GLX backend).
//   picom --backend glx --window-shader-fg ~/path/to/pixelate.glsl
// Change BLOCK to make the pixels bigger or smaller.

#define BLOCK 12.0

in vec2 texcoord;            // texture coordinate of this fragment, in pixels
uniform sampler2D tex;       // the window contents

vec4 default_post_processing(vec4 c);

vec4 window_shader() {
    vec2 size = vec2(textureSize(tex, 0));
    vec2 center = (floor(texcoord / BLOCK) + 0.5) * BLOCK;
    center = clamp(center, vec2(0.0), size - 1.0);
    return default_post_processing(texelFetch(tex, ivec2(center), 0));
}
