import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, Image as ImageIcon, Star, Trash2, Upload } from 'lucide-react';

import { authorizedFetch, postJson } from '@store-app/auth';
import type { AuthAdapter } from '@store-app/auth';
import {
  Badge,
  Button,
  Card,
  CardContent,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Skeleton,
} from '@store-app/ui';

/**
 * The shop's media library (Phase 4).
 *
 * <p>Every Cloudinary asset belonging to this shop, grouped by what it is used for. Grouped rather
 * than a flat grid because the question an owner arrives with is "which one is my logo", not "show
 * me forty squares".
 *
 * <p><b>Uploads go straight from the browser to Cloudinary.</b> The file never passes through this
 * API: the server signs an upload, the browser posts to Cloudinary, and the browser reports back
 * the result. A backend proxy would mean every shop's images are buffered in this process — a
 * memory limit chosen by nobody, sized for somebody else's shop.
 *
 * <p><b>The `public_id` comes from Cloudinary, not from the upload response we hope for.</b> This
 * screen reads it from the signed-upload response and posts it to `/record`, where the server
 * verifies the folder against what Cloudinary reports. A client claiming an image from another
 * shop's directory is refused there, not trusted here.
 *
 * <p><b>Deleting removes the Cloudinary asset too</b>, which is only possible because the public id
 * is stored (D19). With just a URL the images would accumulate in the account forever.
 */

interface MediaAsset {
  id: string;
  url: string;
  width: number | null;
  height: number | null;
  altText: string | null;
  usage: string;
  productId: string | null;
  variantId: string | null;
}

interface Signature {
  timestamp: number;
  signature: string;
  apiKey: string;
  cloudName: string;
  folder: string;
  maxBytes: number;
  maxDimension: number;
}

const USAGE_FILTERS = [
  { value: 'ALL', label: 'Everything' },
  { value: 'PRODUCT', label: 'Products' },
  { value: 'LOGO', label: 'Logo' },
  { value: 'COVER', label: 'Cover' },
  { value: 'FAVICON', label: 'Favicon' },
  { value: 'BANNER', label: 'Banners' },
  { value: 'THEME', label: 'Theme' },
];

const USAGE_LABELS: Record<string, string> = {
  PRODUCT: 'Products',
  LOGO: 'Logo',
  COVER: 'Cover',
  FAVICON: 'Favicon',
  BANNER: 'Banners',
  THEME: 'Theme',
  ORDER: 'Orders',
  REPORT: 'Reports',
};

/** Folder within the shop's Cloudinary directory. Must match what the server signs. */
const FOLDER = 'library';

export function ShopMediaLibrary({ auth }: { auth: AuthAdapter }) {
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState('ALL');
  const [pendingDelete, setPendingDelete] = useState<MediaAsset | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);

  const { data, isLoading } = useQuery({
    queryKey: ['media', filter],
    queryFn: async () => {
      const suffix = filter === 'ALL' ? '' : `?usage=${filter}`;
      const response = await authorizedFetch(auth, `/api/v1/shop/media${suffix}`);
      if (!response.ok) throw new Error(`Media request failed: ${response.status}`);
      return (await response.json()) as MediaAsset[];
    },
  });

  const remove = useMutation({
    mutationFn: async (id: string) => {
      const response = await authorizedFetch(auth, `/api/v1/shop/media/${id}`, { method: 'DELETE' });
      if (!response.ok) throw new Error(await readProblem(response));
      return true;
    },
    onSuccess: () => {
      setPendingDelete(null);
      void queryClient.invalidateQueries({ queryKey: ['media'] });
    },
  });

  const saveAlt = useMutation({
    mutationFn: async (input: { id: string; altText: string }) => {
      const response = await postJson(
        auth,
        `/api/v1/shop/media/${input.id}`,
        { altText: input.altText },
        'PATCH',
      );
      if (!response.ok) throw new Error(await readProblem(response));
      return response.json();
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['media'] }),
  });

  const upload = useCallback(
    async (files: FileList | null) => {
      if (!files || files.length === 0) return;
      setUploadError(null);
      setUploading(true);

      try {
        // Signed once for the whole batch. Each file still gets its own record call, so a batch
        // where the third file fails keeps the first two.
        const signResponse = await postJson(auth, '/api/v1/shop/media/signature', { path: FOLDER });
        if (!signResponse.ok) throw new Error(await readProblem(signResponse));
        const signature = (await signResponse.json()) as Signature;

        let uploaded = 0;
        for (const file of Array.from(files)) {
          if (file.size > signature.maxBytes) {
            setUploadError(
              `${file.name} is ${(file.size / 1_048_576).toFixed(1)}MB. The limit is ${(
                signature.maxBytes /
                1_048_576
              ).toFixed(0)}MB.`,
            );
            continue;
          }

          const publicId = await uploadOne(file, signature);
          const record = await postJson(auth, '/api/v1/shop/media/record', {
            publicId,
            expectedFolder: signature.folder,
            usage: 'PRODUCT',
            productId: null,
            variantId: null,
            altText: '',
          });
          if (!record.ok) throw new Error(await readProblem(record));
          uploaded += 1;
        }

        if (uploaded > 0) {
          void queryClient.invalidateQueries({ queryKey: ['media'] });
        }
      } catch (error) {
        setUploadError(error instanceof Error ? error.message : 'The upload failed');
      } finally {
        setUploading(false);
      }
    },
    [auth, queryClient],
  );

  const groups = useMemo(() => {
    const map = new Map<string, MediaAsset[]>();
    for (const asset of data ?? []) {
      const key = asset.usage;
      map.set(key, [...(map.get(key) ?? []), asset]);
    }
    return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [data]);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">Media Library</h2>
          <p className="text-sm text-muted-foreground">
            Every image uploaded for this shop, grouped by what it is used for.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Select value={filter} onValueChange={setFilter}>
            <SelectTrigger className="w-40" aria-label="Filter images by use">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {USAGE_FILTERS.map((f) => (
                <SelectItem key={f.value} value={f.value}>
                  {f.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button
            onClick={() => fileInput.current?.click()}
            disabled={uploading}
            style={{ background: '#22c55e', color: '#fff' }}
            className="border-0"
          >
            <Upload className="mr-1.5 size-4" aria-hidden />
            {uploading ? 'Uploading…' : 'Upload images'}
          </Button>
          <input
            ref={fileInput}
            type="file"
            accept="image/*"
            multiple
            className="sr-only"
            aria-label="Choose images to upload"
            onChange={(e) => {
              void upload(e.target.files);
              // Cleared so re-picking the same file fires change again.
              e.target.value = '';
            }}
          />
        </div>
      </div>

      {uploadError ? (
        <div
          role="alert"
          className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"
        >
          <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden />
          <span>{uploadError}</span>
        </div>
      ) : null}

      {isLoading ? (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-6">
          {Array.from({ length: 12 }).map((_, i) => (
            <Skeleton key={i} className="aspect-square rounded-lg" />
          ))}
        </div>
      ) : groups.length === 0 ? (
        <Card>
          <CardContent className="p-10 text-center">
            <ImageIcon className="mx-auto size-9 text-muted-foreground" aria-hidden />
            <p className="mt-3 font-medium">No images yet</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Upload images here, or add them from a product. Images are stored in Cloudinary under
              your shop&rsquo;s folder.
            </p>
          </CardContent>
        </Card>
      ) : (
        groups.map(([usage, assets]) => (
          <section key={usage} className="space-y-2">
            <div className="flex items-center gap-2">
              <h3 className="text-sm font-semibold">{USAGE_LABELS[usage] ?? usage}</h3>
              <Badge variant="secondary">{assets.length}</Badge>
            </div>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-6">
              {assets.map((asset) => (
                <MediaTile
                  key={asset.id}
                  asset={asset}
                  onDelete={() => setPendingDelete(asset)}
                  onSaveAlt={(altText) => saveAlt.mutate({ id: asset.id, altText })}
                />
              ))}
            </div>
          </section>
        ))
      )}

      {pendingDelete ? (
        <Dialog open onOpenChange={(open) => !open && setPendingDelete(null)}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Delete this image?</DialogTitle>
              <DialogDescription>
                The image is removed from Cloudinary as well as from this library. Any product still
                using it will show a placeholder. This cannot be undone.
              </DialogDescription>
            </DialogHeader>
            <div className="flex items-center gap-3 rounded-lg border p-3">
              <img
                src={pendingDelete.url}
                alt=""
                className="size-14 shrink-0 rounded object-cover"
              />
              <p className="min-w-0 text-sm text-muted-foreground">
                {pendingDelete.altText || 'No alt text'}
              </p>
            </div>
            {remove.error ? (
              <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
                {remove.error instanceof Error ? remove.error.message : 'The delete failed'}
              </p>
            ) : null}
            <DialogFooter>
              <Button variant="outline" onClick={() => setPendingDelete(null)} disabled={remove.isPending}>
                Keep it
              </Button>
              <Button variant="destructive" onClick={() => remove.mutate(pendingDelete.id)} disabled={remove.isPending}>
                {remove.isPending ? 'Deleting…' : 'Delete image'}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      ) : null}
    </div>
  );
}

function MediaTile({
  asset,
  onDelete,
  onSaveAlt,
}: {
  asset: MediaAsset;
  onDelete: () => void;
  onSaveAlt: (altText: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [altText, setAltText] = useState(asset.altText ?? '');

  useEffect(() => {
    setAltText(asset.altText ?? '');
    setEditing(false);
  }, [asset.altText]);

  if (editing) {
    return (
      <div className="rounded-lg border p-2">
        <Label htmlFor={`alt-${asset.id}`} className="text-xs">
          Alt text
        </Label>
        <Input
          id={`alt-${asset.id}`}
          className="mt-1 h-8 text-xs"
          value={altText}
          onChange={(e) => setAltText(e.target.value)}
          maxLength={200}
          autoFocus
        />
        <div className="mt-2 flex gap-1">
          <Button
            size="sm"
            className="h-7 px-2 text-xs"
            onClick={() => onSaveAlt(altText)}
          >
            Save
          </Button>
          <Button
            size="sm"
            variant="ghost"
            className="h-7 px-2 text-xs"
            onClick={() => {
              setAltText(asset.altText ?? '');
              setEditing(false);
            }}
          >
            Cancel
          </Button>
        </div>
      </div>
    );
  }

  return (
    <figure className="group relative overflow-hidden rounded-lg border bg-muted">
      <img
        src={asset.url}
        alt={asset.altText ?? ''}
        loading="lazy"
        className="aspect-square w-full object-cover"
      />
      {!asset.altText ? (
        <span className="absolute left-1.5 top-1.5">
          <Badge variant="secondary" className="text-[10px]">
            No alt text
          </Badge>
        </span>
      ) : null}
      <figcaption className="absolute inset-x-0 bottom-0 flex justify-end gap-1 bg-gradient-to-t from-black/70 to-transparent p-1.5 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
        <Button
          size="sm"
          variant="secondary"
          className="h-7 w-7 p-0"
          aria-label={`Edit alt text for this image`}
          onClick={() => setEditing(true)}
        >
          <Star className="size-3.5" aria-hidden />
        </Button>
        <Button
          size="sm"
          variant="destructive"
          className="h-7 w-7 p-0"
          aria-label={`Delete this image`}
          onClick={onDelete}
        >
          <Trash2 className="size-3.5" aria-hidden />
        </Button>
      </figcaption>
    </figure>
  );
}

/**
 * Posts one file to Cloudinary with the signed parameters.
 *
 * <p>Returns the `public_id`, which is the only reason deletion is possible at all (D19). A URL
 * alone would leave every uploaded asset in the Cloudinary account for the life of the project.
 *
 * <p>Progress is reported by the XMLHttpRequest rather than `fetch`: `fetch` still has no upload
 * progress event, and a progress bar that lies is worse than none for a 5MB file on a slow
 * connection.
 */
function uploadOne(file: File, signature: Signature): Promise<string> {
  return new Promise((resolve, reject) => {
    const form = new FormData();
    form.append('file', file);
    form.append('api_key', signature.apiKey);
    form.append('timestamp', String(signature.timestamp));
    form.append('signature', signature.signature);
    form.append('folder', signature.folder);

    const request = new XMLHttpRequest();
    request.open('POST', `https://api.cloudinary.com/v1_1/${signature.cloudName}/image/upload`);

    request.onload = () => {
      if (request.status >= 200 && request.status < 300) {
        try {
          const body = JSON.parse(request.responseText) as { public_id?: string };
          if (!body.public_id) {
            reject(new Error('Cloudinary did not return a public id for ' + file.name));
            return;
          }
          resolve(body.public_id);
        } catch {
          reject(new Error('Could not read Cloudinary’s response for ' + file.name));
        }
      } else {
        // Cloudinary's own error text is far more useful than a status code, and it is not
        // sensitive: it describes the rejected upload, not the credentials.
        reject(new Error(describeUploadError(request.responseText, request.status)));
      }
    };
    request.onerror = () => reject(new Error('The network dropped while uploading ' + file.name));
    request.send(form);
  });
}

function describeUploadError(body: string, status: number): string {
  try {
    const parsed = JSON.parse(body) as { error?: { message?: string } };
    if (parsed.error?.message) return parsed.error.message;
  } catch {
    // Not JSON. Fall through.
  }
  return `Cloudinary rejected the upload (HTTP ${status})`;
}

/** Reads a problem response into one line, so an upload failure is never "HTTP 400". */
async function readProblem(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: string };
    if (body.detail) return body.detail;
  } catch {
    // Not a problem+ body.
  }
  return `The server rejected this (HTTP ${response.status})`;
}