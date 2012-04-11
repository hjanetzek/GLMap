precision mediump float;
uniform float width;
varying vec2 v_st;
varying vec4 v_color;
void main() {
	vec2 st_width = fwidth(v_st);
	float fuzz = max(st_width.s, st_width.t);
	float alpha = 1.0 - smoothstep(width - fuzz, width + fuzz, length(v_st));
	vec4 color = v_color * alpha;
	if (color.a < 0.2) {
	    discard;
	} else {
	    gl_FragColor = color;\n
	} 
}
