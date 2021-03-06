package pl.asie.ctif;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.*;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import pl.asie.ctif.colorspace.Colorspace;
import pl.asie.ctif.platform.Platform;
import pl.asie.ctif.platform.PlatformComputerCraft;
import pl.asie.ctif.platform.PlatformOpenComputers;
import pl.asie.ctif.platform.PlatformZXSpectrum;

public class Main {
	private static class Parameters {
		@Parameter(names = {"--palette-sampling-resolution"}, description = "The sampling resolution for palette generation. 0 = full image (1/4x1/4 image in -O3+)")
		private int paletteSamplingResolution = 0;

		@Parameter(names = {"--threads"}, description = "Amount of threads to create")
		private int threads = Runtime.getRuntime().availableProcessors();

		@Parameter(names = {"--palette-export"}, description = "File to export the palette to.")
		private String paletteExport;

		@Parameter(names = {"--palette"}, description = "File to load the palette from.")
		private String palette;

		@Parameter(names = {"-m", "--mode"}, description = "Target platform (cc, cc-paletted, oc-tier2, oc-tier3)")
		private String mode = "oc-tier3";

		@Parameter(names = {"-O", "--optimization-level"}, description = "Optimization level [primarily 0-4]. Larger levels = less accurate but faster generated images. Default is 1.")
		private int optimizationLevel = 1;

		@Parameter(names = {"--colorspace"}, description = "Colorspace (rgb, yuv, yiq)")
		private String colorspace = "yiq";

		@Parameter(names = {"--dither-mode"}, description = "Dither mode (none, error, ordered)")
		private Converter.DitherMode ditherMode = Converter.DitherMode.ERROR;

		@Parameter(names = {"--dither-type"}, description = "Dither type (error: floyd-steinberg, sierra-lite; ordered: 2x2, 4x4, 8x8)")
		private String ditherType;

		@Parameter(names = {"--dither-level"}, description = "Dither level for error-type dither. 0 = off, 1 = full (default)")
		private float ditherLevel = 1.0f;

		@Parameter(names = {"-d", "--debug"}, description = "Enable debugging", hidden = true)
		private boolean debug = false;

		@Parameter(names = {"-W", "--width"}, description = "Output image width")
		private int w;

		@Parameter(names = {"-H", "--height"}, description = "Output image height")
		private int h;

		@Parameter(names = {"-N", "--no-aspect"}, description = "Ignore aspect ratio")
		private boolean ignoreAspectRatio = false;

		@Parameter(names = {"-o", "--output"}, description = "Output filename")
		private String outputFilename;

		@Parameter(names = {"-P", "--preview"}, description = "Preview image filename")
		private String previewFilename;

		@Parameter(description = "Input file")
		private List<String> files = new ArrayList<>();

		@Parameter(names = {"-h", "--help"}, description = "Print usage", help = true)
		private boolean help;

		@Parameter(names = {"--resize-mode"}, description = "Resize mode")
		private ResizeMode resizeMode;
	}

	public enum ResizeMode {
		SPEED,
		QUALITY_NATIVE,
		QUALITY
	};

	public static Colorspace COLORSPACE = null;
	public static Platform PLATFORM = null;
	public static int OPTIMIZATION_LEVEL = 1;
	private static final Map<String, float[]> DITHER_ARRAYS = new HashMap<>();
	private static final Map<String, Platform> PLATFORMS = new HashMap<>();
	private static final Map<String, Colorspace> COLORSPACES = new HashMap<>();

	static {
		PLATFORMS.put("cc", new PlatformComputerCraft(false));
		PLATFORMS.put("cc-paletted", new PlatformComputerCraft(true));
//		PLATFORMS.put("oc-tier1", new PlatformOpenComputers(1));
		PLATFORMS.put("oc-tier2", new PlatformOpenComputers(2));
		PLATFORMS.put("oc-tier3", new PlatformOpenComputers(3));
		PLATFORMS.put("zxspectrum", new PlatformZXSpectrum(0));
		PLATFORMS.put("zxspectrum-dark", new PlatformZXSpectrum(1));

		COLORSPACES.put("rgb", Colorspace.RGB);
		COLORSPACES.put("yuv", Colorspace.YUV);
		COLORSPACES.put("yiq", Colorspace.YIQ);

		DITHER_ARRAYS.put("floyd-steinberg", new float[] {
				0, 0, 0,
				0, 0, 7f/16f,
				3f/16f, 5f/16f, 1f/16f
		});
		DITHER_ARRAYS.put("sierra-lite", new float[] {
				0, 0, 0,
				0, 0, 0.5f,
				0.25f, 0.25f, 0
		});
		DITHER_ARRAYS.put("checks", new float[] {
				0, 1,
				1, 0,
				2
		});
		DITHER_ARRAYS.put("2x2", new float[] {
				0, 2,
				3, 1,
				4
		});
		DITHER_ARRAYS.put("3x3", new float[] {
				0, 7, 3,
				6, 5, 2,
				4, 1, 8,
				9
		});
		DITHER_ARRAYS.put("4x4", new float[] {
				0, 8, 2, 10,
				12, 4, 14, 6,
				3, 11, 1, 9,
				15, 7, 13, 5,
				16
		});
		DITHER_ARRAYS.put("8x8", new float[] {
				0, 48, 12, 60, 3, 51, 15, 63,
				32, 16, 44, 28, 35, 19, 47, 31,
				8, 56, 4, 52, 11, 59, 7, 55,
				40, 24, 36, 20, 43, 27, 39, 23,
				2, 50, 14, 62, 1, 49, 13, 61,
				34, 18, 46, 30, 33, 17, 45, 29,
				10, 58, 6, 54, 9, 57, 5, 53,
				42, 26, 38, 22, 41, 25, 37, 21,
				64
		});

		for (int i = 3; i <= 8; i++) {
			float[] arrL = new float[i * i + 1];
			float[] arrR = new float[i * i + 1];
			float[] arrS = new float[i * i + 1];
			arrL[i * i] = arrR[i * i] = i;
			arrS[i * i] = i * i;
			for (int j = 0; j < i; j++) {
				for (int k = 0; k < i; k++) {
					arrL[k * i + j] = ((i - 1 - j) + (i - k)) % i;
					arrR[k * i + j] = (j + (i - k)) % i;
					arrS[k * i + j] = Math.max(k, j) * Math.max(k, j);
				}
			}

			DITHER_ARRAYS.put("diag-l-" + i + "x" + i, arrL);
			DITHER_ARRAYS.put("diag-r-" + i + "x" + i, arrR);
			DITHER_ARRAYS.put("square-" + i + "x" + i, arrS);
		}

		for (int i = 3; i <= 8; i += 2) {
			float[] arrD = new float[i * i + 1];
			arrD[i * i] = i * i;
			int center = i / 2;
			for (int j = 0; j < i; j++) {
				for (int k = 0; k < i; k++) {
					arrD[k * i + j] = Math.abs(j - center) + Math.abs(k - center);
					arrD[k * i + j] *= arrD[k * i + j];
				}
			}
			DITHER_ARRAYS.put("diamond-" + i + "x" + i, arrD);
		}

		DITHER_ARRAYS.put("diagl-4x4", new float[] {
				3, 2, 1, 0,
				2, 1, 0, 3,
				1, 0, 3, 2,
				0, 3, 2, 1,
				4
		});
		DITHER_ARRAYS.put("diagr-4x4", new float[] {
				0, 1, 2, 3,
				3, 0, 1, 2,
				2, 3, 0, 1,
				1, 2, 3, 0,
				4
		});
	}

    public static boolean DEBUG = false;

	private static Parameters params;

	private static int rCeil(int x, int y) {
		if (x % y > 0) {
			return x - (x % y) + y;
		} else {
			return x;
		}
	}

	public static void main(String[] args) {
		params = new Parameters();
		JCommander jCommander = new JCommander(params, args);

		if (params.help) {
			jCommander.usage();
			System.exit(0);
		}

		PLATFORM = PLATFORMS.get(params.mode.toLowerCase());
		COLORSPACE = COLORSPACES.get(params.colorspace.toLowerCase());

		if (params.files.size() == 0) {
			System.err.println("No input file specified!");
			System.exit(1);
		}

		if (PLATFORM == null) {
			System.err.println(String.format("Invalid mode: %s", params.mode));
			System.exit(1);
		}

		OPTIMIZATION_LEVEL = params.optimizationLevel;
		DEBUG = params.debug;
		if (params.ditherType == null) {
			switch (params.ditherMode) {
				case ORDERED:
					params.ditherType = "4x4";
					break;
				default:
					params.ditherType = "floyd-steinberg";
					break;
			}
		}

		BufferedImage image = Utils.loadImage(params.files.get(0));
		if (image == null) {
			System.err.println(String.format("Could not load image: %s", params.files.get(0)));
			System.exit(1);
		}

		Color[] palette = PLATFORM.getPalette();
		params.w = (params.w > 0) ? rCeil(params.w, PLATFORM.getCharWidth()) : 0;
		params.h = (params.h > 0) ? rCeil(params.h, PLATFORM.getCharHeight()) : 0;

		if (params.w == 0) params.w = PLATFORM.getWidthPx();
		if (params.h == 0) params.h = PLATFORM.getHeightPx();

		if (!params.ignoreAspectRatio) {
			float x = (params.ignoreAspectRatio ? PLATFORM.getDefaultAspectRatio() : (float) image.getWidth() / image.getHeight());
			float y = 1.0f;
			float a = Math.min(Math.min(
					(float) params.w / x,
					(float) params.h / y),
					(float) Math.sqrt((float) PLATFORM.getCharsPx() / (x * y)));
			params.w = rCeil((int) Math.floor(x * a), PLATFORM.getCharWidth());
			params.h = rCeil((int) Math.floor(y * a), PLATFORM.getCharHeight());
		}

		System.out.println(params.w + " " + params.h);

		if (params.w * params.h > PLATFORM.getCharsPx()) {
			System.err.println(String.format("Size too large: %dx%d (maximum size: %d pixels)", params.w, params.h, PLATFORM.getCharsPx()));
			System.exit(1);
		} else if (params.w > PLATFORM.getWidthPx()) {
			System.err.println(String.format("Width too large: %d (maximum width: %d)", params.w, PLATFORM.getWidthPx()));
			System.exit(1);
		} else if (params.h > PLATFORM.getHeightPx()) {
			System.err.println(String.format("Height too large: %d (maximum height: %d)", params.h, PLATFORM.getHeightPx()));
			System.exit(1);
		}

		int width = params.w;
		int height = params.h;

		if (Main.DEBUG) {
			System.err.println("Using " + params.threads + " threads.");
		}

		System.err.println("Resizing image...");
		long timeR = System.currentTimeMillis();

		BufferedImage resizedImage;
		if (image.getWidth() == width && image.getHeight() == height) {
			resizedImage = image;
		} else if (params.resizeMode == ResizeMode.SPEED) {
			resizedImage = Utils.resizeBox(image, width, height);
		} else {
			resizedImage = Utils.resize(image, width, height, params.resizeMode == ResizeMode.QUALITY_NATIVE);
		}

		timeR = System.currentTimeMillis() - timeR;
		if (DEBUG) {
			System.err.println("Image resize time: " + timeR + " ms");
		}

		if (PLATFORM.getCustomColorCount() > 0) {
			if (params.palette != null) {
				System.err.println("Reading palette...");
				try {
					FileInputStream inputStream = new FileInputStream(new File(params.palette));
					for (int i = 0; i < PLATFORM.getCustomColorCount(); i++) {
						int red = inputStream.read();
						int green = inputStream.read();
						int blue = inputStream.read();
						palette[i] = new Color(red, green, blue);
					}
					inputStream.close();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			} else {
				long time = System.currentTimeMillis();
				System.err.println("Generating palette...");
				PaletteGeneratorKMeans generator = new PaletteGeneratorKMeans(resizedImage, palette, PLATFORM.getCustomColorCount(), params.paletteSamplingResolution);
				palette = generator.generate(params.threads);
				time = System.currentTimeMillis() - time;
				if (DEBUG) {
					System.err.println("Palette generation time: " + time + " ms");
				}
			}

			if (params.paletteExport != null) {
				System.err.println("Saving palette...");
				try {
					FileOutputStream outputStream = new FileOutputStream(new File(params.paletteExport));
					for (int i = 0; i < palette.length; i++) {
						outputStream.write(palette[i].getRed());
						outputStream.write(palette[i].getGreen());
						outputStream.write(palette[i].getBlue());
					}
					outputStream.close();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		try {
			BufferedImage outputImage = resizedImage;
			float[] ditherArray = DITHER_ARRAYS.get(params.ditherType.toLowerCase());

			if (params.ditherLevel == 0) {
				params.ditherMode = Converter.DitherMode.NONE;
			} else if (params.ditherLevel != 1) {
				ditherArray = Arrays.copyOf(ditherArray, ditherArray.length);

				switch (params.ditherMode) {
					case ERROR:
						for (int i = 0; i < ditherArray.length; i++) {
							ditherArray[i] *= params.ditherLevel;
						}
						break;
					case ORDERED:
						float newScale = params.ditherLevel;
						float newOffset = ditherArray.length * ((1 - params.ditherLevel) / 2.0f);
						for (int i = 0; i < ditherArray.length; i++) {
							if (ditherArray[i] > 0) {
								ditherArray[i] = (ditherArray[i] - 1) * newScale + newOffset;
							}
						}
						break;
				}
			}

			System.err.println("Converting image...");

			long time = System.currentTimeMillis();
			Converter writer = new Converter(palette, resizedImage,
					params.ditherMode,
					ditherArray
			);

			try {
				outputImage = writer.write(new FileOutputStream(params.outputFilename != null ? params.outputFilename : params.files.get(0) + ".ctif"));
			} catch (Exception e) {
				e.printStackTrace();
			}
			time = System.currentTimeMillis() - time;
			if (DEBUG) {
				System.err.println("Image conversion time: " + time + " ms");
			}

			if (params.previewFilename != null) {
				Utils.saveImage(Utils.resizeBox(outputImage, width * 2, height * 2), new File(params.previewFilename).getAbsolutePath());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}