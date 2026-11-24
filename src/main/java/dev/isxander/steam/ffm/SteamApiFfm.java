package dev.isxander.steam.ffm;

import dev.isxander.steam.SteamApi;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public class SteamApiFfm implements SteamApi {
    /// `typedef char SteamErrMsg[1024];`
    private static final long STEAM_ERR_MSG_SIZE = 1024;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    /// Uses the app ID from the environment or `steam_appid.txt`.
    public SteamApiFfm() {
    }

    /// Sets the `SteamAppId` and `SteamGameId` environment variables of the current process so that
    /// `steam_appid.txt` is not needed. Must be constructed before the first call to [#SteamAPI_InitFlat].
    public SteamApiFfm(int appId) {
        String id = Integer.toString(appId);
        setEnv("SteamAppId", id);
        setEnv("SteamGameId", id);
    }

    /// Steam natives, loaded on first use so environment variables can be set beforehand.
    private static final class Natives {
        private static final SymbolLookup LOOKUP = SymbolLookup.libraryLookup(extractNativeLibrary(), Arena.global());

        /// `ESteamAPIInitResult SteamAPI_InitFlat(SteamErrMsg *pOutErrMsg);`
        private static final MethodHandle STEAMAPI_INIT_FLAT = LINKER.downcallHandle(
                LOOKUP.findOrThrow("SteamAPI_InitFlat"),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
        );
    }

    /// Process environment setter: `setenv` from libc, or `SetEnvironmentVariableW` from kernel32 on Windows.
    private static final class Env {
        /// `int setenv(const char *name, const char *value, int overwrite);` or
        /// `BOOL SetEnvironmentVariableW(LPCWSTR lpName, LPCWSTR lpValue);`
        private static final MethodHandle SET_ENV = WINDOWS
                ? LINKER.downcallHandle(
                        SymbolLookup.libraryLookup("kernel32", Arena.global()).findOrThrow("SetEnvironmentVariableW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
                : LINKER.downcallHandle(
                        LINKER.defaultLookup().findOrThrow("setenv"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    }

    private static void setEnv(String name, String value) {
        try (Arena arena = Arena.ofConfined()) {
            boolean ok;
            if (WINDOWS) {
                ok = (int) Env.SET_ENV.invokeExact(
                        arena.allocateFrom(name, StandardCharsets.UTF_16LE),
                        arena.allocateFrom(value, StandardCharsets.UTF_16LE)) != 0;
            } else {
                ok = (int) Env.SET_ENV.invokeExact(arena.allocateFrom(name), arena.allocateFrom(value), 1) == 0;
            }
            if (!ok) {
                throw new IllegalStateException("Failed to set environment variable " + name);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to set environment variable " + name, t);
        }
    }

    @Override
    public int SteamAPI_InitFlat(String[] pOutErrMsg) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment errMsg = arena.allocate(STEAM_ERR_MSG_SIZE);
            int result = (int) Natives.STEAMAPI_INIT_FLAT.invokeExact(errMsg);
            if (pOutErrMsg != null && pOutErrMsg.length > 0) {
                pOutErrMsg[0] = errMsg.getString(0);
            }
            return result;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke SteamAPI_InitFlat", t);
        }
    }

    private static Path extractNativeLibrary() {
        String resource = nativeLibraryResource();
        String fileName = resource.substring(resource.lastIndexOf('/') + 1);

        try (InputStream in = SteamApiFfm.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Native library not found on classpath: " + resource);
            }
            Path dir = Files.createTempDirectory("controlify-steam");
            Path lib = dir.resolve(fileName);
            Files.copy(in, lib, StandardCopyOption.REPLACE_EXISTING);
            lib.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return lib;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract native library " + resource, e);
        }
    }

    private static String nativeLibraryResource() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean x86 = arch.equals("x86") || arch.matches("i[3-6]86");

        if (os.contains("win")) {
            if (x64) return "/win64/steam_api64.dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            return "/osx/libsteam_api.dylib"; // universal binary
        } else if (os.contains("linux")) {
            if (x64) return "/linux64/libsteam_api.so";
            if (arm64) return "/linuxarm64/libsteam_api.so";
            if (x86) return "/linux32/libsteam_api.so";
        }
        throw new UnsupportedOperationException("Unsupported platform for Steam API: " + os + " / " + arch);
    }
}
