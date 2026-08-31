import { useEffect, useState } from 'react'
import { ImageOff } from 'lucide-react'
import { cn } from '@/utils/cn'

interface ImageWithFallbackProps {
  src: string | null
  alt: string
  className?: string
  fallbackClassName?: string
}

/**
 * Resource images are private S3 objects served through short-lived presigned
 * GET URLs (see ResourceResponse.imageUrl / AdminResourceImageService). Those
 * URLs are never persisted anywhere client-side — they simply flow through
 * TanStack Query's normal cache and get replaced on refetch. This component
 * only needs to render whatever URL it's given and degrade gracefully when
 * there is none, or when the URL has expired / failed to load.
 */
export function ImageWithFallback({ src, alt, className, fallbackClassName }: ImageWithFallbackProps) {
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    setFailed(false)
  }, [src])

  if (!src || failed) {
    return (
      <div
        className={cn(
          'flex aspect-video w-full items-center justify-center bg-neutral-100 text-neutral-300',
          className,
          fallbackClassName,
        )}
      >
        <ImageOff className="size-8" aria-hidden="true" />
      </div>
    )
  }

  return (
    <img
      src={src}
      alt={alt}
      loading="lazy"
      onError={() => setFailed(true)}
      className={cn('aspect-video w-full bg-neutral-100 object-cover', className)}
    />
  )
}
