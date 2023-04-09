package net.irisshaders.iris.pipeline;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.ARBTextureSwizzle;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;

import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ExtendedShader extends ShaderInstance implements ShaderInstanceInterface {
	private static final Matrix4f identity;
	private static ExtendedShader lastApplied;

	static {
		identity = new Matrix4f();
		identity.identity();
	}

	private final boolean intensitySwizzle;
	private final List<BufferBlendOverride> bufferBlendOverrides;
	private final boolean hasOverrides;
	private final Uniform modelViewInverse;
	private final Uniform projectionInverse;
	private final Uniform normalMatrix;
	private final CustomUniforms customUniforms;
	private final ShaderAttributeInputs inputs;
	private final Vector3f chunkOffset = new Vector3f();
	NewWorldRenderingPipeline parent;
	ProgramSamplers samplers;
	ProgramImages images;
	GlFramebuffer writingToBeforeTranslucent;
	GlFramebuffer writingToAfterTranslucent;
	GlFramebuffer baseline;
	BlendModeOverride blendModeOverride;
	float alphaTest;
	Matrix4f tempMatrix4f = new Matrix4f();
	Matrix3f tempMatrix3f = new Matrix3f();
	float[] tempFloats = new float[16];
	float[] tempFloats2 = new float[9];
	private Program geometry;

	public ExtendedShader(ResourceProvider resourceFactory, String string, VertexFormat vertexFormat,
						  GlFramebuffer writingToBeforeTranslucent, GlFramebuffer writingToAfterTranslucent,
						  GlFramebuffer baseline, BlendModeOverride blendModeOverride, AlphaTest alphaTest,
						  BiConsumer<SamplerHolder, ImageHolder> samplerCreator, boolean isIntensity,
						  NewWorldRenderingPipeline parent, ShaderAttributeInputs inputs, @Nullable List<BufferBlendOverride> bufferBlendOverrides, CustomUniforms customUniforms) throws IOException {
		super(resourceFactory, string, vertexFormat);

		int programId = this.getId();

		ProgramSamplers.Builder samplerBuilder = ProgramSamplers.builder(programId, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
		ProgramImages.Builder builder = ProgramImages.builder(programId);
		samplerCreator.accept(samplerBuilder, builder);

		this.customUniforms = customUniforms;
		samplers = samplerBuilder.build();
		images = builder.build();
		this.writingToBeforeTranslucent = writingToBeforeTranslucent;
		this.writingToAfterTranslucent = writingToAfterTranslucent;
		this.baseline = baseline;
		this.blendModeOverride = blendModeOverride;
		this.bufferBlendOverrides = bufferBlendOverrides;
		this.hasOverrides = bufferBlendOverrides != null && !bufferBlendOverrides.isEmpty();
		this.alphaTest = alphaTest.getReference();
		this.parent = parent;
		this.inputs = inputs;

		this.modelViewInverse = this.getUniform("ModelViewMatInverse");
		this.projectionInverse = this.getUniform("ProjMatInverse");
		this.normalMatrix = this.getUniform("NormalMat");

		this.intensitySwizzle = isIntensity;

		GL45C.glProgramUniform1f(programId, GlStateManager._glGetUniformLocation(programId, "iris_currentAlphaTest"), alphaTest.getReference());
	}

	public boolean isIntensitySwizzle() {
		return intensitySwizzle;
	}

	@Override
	public void clear() {
		ProgramSamplers.clearActiveSamplers();
		lastApplied = null;

		if (this.blendModeOverride != null || hasOverrides) {
			BlendModeOverride.restore();
		}

		Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
	}

	@Override
	public void apply() {
		if (lastApplied != this) {
			lastApplied = this;
			ProgramManager.glUseProgram(this.getId());
		}

		if (intensitySwizzle) {
			IrisRenderSystem.texParameteriv(RenderSystem.getShaderTexture(0), TextureType.TEXTURE_2D.getGlType(), ARBTextureSwizzle.GL_TEXTURE_SWIZZLE_RGBA,
				new int[]{GL30C.GL_RED, GL30C.GL_RED, GL30C.GL_RED, GL30C.GL_RED});
		}

		IrisRenderSystem.bindTextureToUnit(TextureType.TEXTURE_2D.getGlType(), IrisSamplers.ALBEDO_TEXTURE_UNIT, RenderSystem.getShaderTexture(0));
		IrisRenderSystem.bindTextureToUnit(TextureType.TEXTURE_2D.getGlType(), IrisSamplers.OVERLAY_TEXTURE_UNIT, RenderSystem.getShaderTexture(1));
		IrisRenderSystem.bindTextureToUnit(TextureType.TEXTURE_2D.getGlType(), IrisSamplers.LIGHTMAP_TEXTURE_UNIT, RenderSystem.getShaderTexture(2));

		if (PROJECTION_MATRIX != null) {
			if (projectionInverse != null) {
				projectionInverse.set(tempMatrix4f.set(PROJECTION_MATRIX.getFloatBuffer()).invert().get(tempFloats));
			}
		} else {
			if (projectionInverse != null) {
				projectionInverse.set(identity);
			}
		}

		if (MODEL_VIEW_MATRIX != null) {
			if (modelViewInverse != null) {
				modelViewInverse.set(tempMatrix4f.set(MODEL_VIEW_MATRIX.getFloatBuffer()).invert().get(tempFloats));
			}

			if (normalMatrix != null) {
				normalMatrix.set(tempMatrix3f.set(tempMatrix4f.set(MODEL_VIEW_MATRIX.getFloatBuffer())).invert().transpose().get(tempFloats2));
			}
		} else {
			if (modelViewInverse != null) {
				modelViewInverse.set(identity);
			}

			if (normalMatrix != null) {
				normalMatrix.set(identity);
			}
		}

		uploadIfNotNull(projectionInverse);
		uploadIfNotNull(modelViewInverse);
		uploadIfNotNull(normalMatrix);

		List<Uniform> uniformList = super.uniforms;
		for (Uniform uniform : uniformList) {
			uploadIfNotNull(uniform);
		}

		samplers.update();
		images.update();


		if (this.blendModeOverride != null) {
			this.blendModeOverride.apply();
		}

		if (hasOverrides) {
			bufferBlendOverrides.forEach(BufferBlendOverride::apply);
		}

		if (parent.isBeforeTranslucent) {
			writingToBeforeTranslucent.bind();
		} else {
			writingToAfterTranslucent.bind();
		}
	}

	@Nullable
	@Override
	public Uniform getUniform(String name) {
		// Prefix all uniforms with Iris to help avoid conflicts with existing names within the shader.
		return super.getUniform("iris_" + name);
	}

	private void uploadIfNotNull(Uniform uniform) {
		if (uniform != null) {
			uniform.upload();
		}
	}

	@Override
	public void close() {
		super.close();
	}

	@Override
	public void attachToProgram() {
		super.attachToProgram();
		if (this.geometry != null) {
			this.geometry.attachToShader(this);
		}
	}

	@Override
	public void iris$createGeometryShader(ResourceProvider factory, String name) throws IOException {
		factory.getResource(new ResourceLocation("minecraft", name + "_geometry.gsh")).ifPresent(geometry -> {
			try {
				this.geometry = Program.compileShader(IrisProgramTypes.GEOMETRY, name, geometry.open(), geometry.sourcePackId(), new GlslPreprocessor() {
					@Nullable
					@Override
					public String applyImport(boolean bl, String string) {
						return null;
					}
				});
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}

	public Program getGeometry() {
		return this.geometry;
	}

	public boolean hasActiveImages() {
		return images.getActiveImages() > 0;
	}
}