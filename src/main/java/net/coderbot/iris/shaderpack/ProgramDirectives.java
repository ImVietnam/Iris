package net.coderbot.iris.shaderpack;

import java.util.Optional;

public class ProgramDirectives {
	private int[] drawBuffers;
	private float viewportScale;

	ProgramDirectives(ShaderPack.ProgramSource source) {
		// First try to find it in the fragment source, then in the vertex source.
		// If there's no explicit declaration, then by default /* DRAWBUFFERS:0 */ is inferred.
		drawBuffers = findDrawbuffersDirective(source.getFragmentSource())
			.orElseGet(() -> findDrawbuffersDirective(source.getVertexSource()).orElse(new int[]{0}));
		viewportScale = 1.0f;

		// TODO: Sildur's hardcoding
		if (source.getName().contains("composite3")) {
			viewportScale = 0.25f;
		}
	}

	private static Optional<int[]> findDrawbuffersDirective(Optional<String> stageSource) {
		return stageSource
			.flatMap(fragment -> DirectiveParser.findDirective(fragment, "DRAWBUFFERS"))
			.map(String::toCharArray)
			.map(ProgramDirectives::parseDigits);
	}

	private static int[] parseDigits(char[] directiveChars) {
		int[] buffers = new int[directiveChars.length];
		int index = 0;

		for (char buffer : directiveChars) {
			buffers[index++] = Character.digit(buffer, 10);
		}

		return buffers;
	}

	public int[] getDrawBuffers() {
		return drawBuffers;
	}

	public float getViewportScale() {
		return viewportScale;
	}
}
