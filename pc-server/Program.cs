using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.StaticFiles;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddCors(options => options.AddDefaultPolicy(policy =>
    policy.AllowAnyHeader().AllowAnyMethod().AllowAnyOrigin()));
var app = builder.Build();
app.UseCors();

var configPath = Path.Combine(app.Environment.ContentRootPath, "appsettings.json");
var configuredFolders = new List<string>();
var mediaBaseUrl = "";
try
{
    using var json = System.Text.Json.JsonDocument.Parse(File.ReadAllText(configPath));
    if (json.RootElement.TryGetProperty("MediaBaseUrl", out var baseUrl)) mediaBaseUrl = baseUrl.GetString() ?? "";
    if (json.RootElement.TryGetProperty("MediaFolders", out var folders) && folders.ValueKind == System.Text.Json.JsonValueKind.Array)
        configuredFolders = folders.EnumerateArray().Select(x => x.GetString()).Where(x => !string.IsNullOrWhiteSpace(x)).Cast<string>().ToList();
}
catch { }

var supportedExtensions = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
    { ".mp4", ".m4v", ".mov", ".mkv", ".avi", ".webm", ".mpeg", ".mpg", ".ts" };
var contentTypes = new FileExtensionContentTypeProvider();

string MakeId(string path) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(path))))[..24].ToLowerInvariant();

app.MapGet("/api/health", () => Results.Ok(new { status = "ok", name = "CastBox", folders = configuredFolders.Count }));

app.MapGet("/api/library", () =>
{
    var files = new List<object>();
    foreach (var root in configuredFolders)
    {
        try
        {
            var fullRoot = Path.GetFullPath(Environment.ExpandEnvironmentVariables(root));
            if (!Directory.Exists(fullRoot)) continue;
            foreach (var file in Directory.EnumerateFiles(fullRoot, "*", SearchOption.AllDirectories))
            {
                var ext = Path.GetExtension(file);
                if (!supportedExtensions.Contains(ext)) continue;
                var info = new FileInfo(file);
                files.Add(new
                {
                    id = MakeId(file),
                    title = Path.GetFileNameWithoutExtension(file),
                    fileName = info.Name,
                    extension = ext.TrimStart('.').ToLowerInvariant(),
                    folder = Path.GetDirectoryName(file),
                    sizeBytes = info.Length,
                    modifiedUtc = info.LastWriteTimeUtc,
                    url = $"{mediaBaseUrl.TrimEnd('/')}/api/media/{MakeId(file)}"
                });
            }
        }
        catch (Exception ex) { app.Logger.LogWarning(ex, "Could not scan media folder {Folder}", root); }
    }
    return Results.Ok(files);
});

app.MapGet("/api/media/{id}", async (string id, HttpContext context) =>
{
    foreach (var root in configuredFolders)
    {
        try
        {
            var fullRoot = Path.GetFullPath(Environment.ExpandEnvironmentVariables(root));
            if (!Directory.Exists(fullRoot)) continue;
            foreach (var file in Directory.EnumerateFiles(fullRoot, "*", SearchOption.AllDirectories))
            {
                if (!supportedExtensions.Contains(Path.GetExtension(file)) || !string.Equals(MakeId(file), id, StringComparison.OrdinalIgnoreCase)) continue;
                var stream = new FileStream(file, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 1024 * 128, FileOptions.Asynchronous | FileOptions.SequentialScan);
                if (!contentTypes.TryGetContentType(file, out var mime)) mime = "application/octet-stream";
                // Chromecast receivers need a meaningful video MIME type. MKV/AVI are not generally
                // supported by the Default Media Receiver; this endpoint does not transcode them.
                if (Path.GetExtension(file).Equals(".mkv", StringComparison.OrdinalIgnoreCase) || Path.GetExtension(file).Equals(".avi", StringComparison.OrdinalIgnoreCase)) mime = "video/x-matroska";
                return Results.File(stream, mime, enableRangeProcessing: true);
            }
        }
        catch (Exception ex) { app.Logger.LogWarning(ex, "Could not locate media ID {Id}", id); }
    }
    return Results.NotFound();
});

// Serve the production web build if present. During development, use the Vite dev server.
var webRoot = Path.Combine(app.Environment.ContentRootPath, "wwwroot");
if (Directory.Exists(webRoot))
{
    app.UseDefaultFiles();
    app.UseStaticFiles();
    app.MapFallbackToFile("index.html");
}

app.Run();
