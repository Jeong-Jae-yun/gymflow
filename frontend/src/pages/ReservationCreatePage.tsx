import { useEffect, useId, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, CalendarClock, CheckCircle2, Clock3 } from 'lucide-react'
import { Button, Card, EmptyState, ErrorState, FormField, Input, PageSpinner, Select, Skeleton } from '@/components/ui'
import { useResourceAvailability, useResourceDetail } from '@/features/resources/hooks'
import { useCreateReservation } from '@/features/reservations/hooks'
import { useRegisterWaitingQueue } from '@/features/waitingQueue/hooks'
import { useToast } from '@/context/useToast'
import { toBackendDateTime, formatDateTime, formatDuration, formatTime } from '@/utils/date'
import { getErrorMessage } from '@/utils/getErrorMessage'
import { resourceTypeLabels } from '@/utils/labels'
import { cn } from '@/utils/cn'
import type { ApiError, AvailabilitySlot } from '@/types'

type Step = 'form' | 'review' | 'conflict' | 'success'
type DayPeriod = '오전' | '오후' | '저녁'

const PERIOD_ORDER: DayPeriod[] = ['오전', '오후', '저녁']

function todayDateString(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}

/** Buckets a slot by its hour-of-day so the grid can be split into 오전/오후/저녁 sections. */
function periodOf(slot: AvailabilitySlot): DayPeriod {
  const hour = Number(slot.startAt.slice(11, 13))
  if (hour < 12) return '오전'
  if (hour < 18) return '오후'
  return '저녁'
}

export function ReservationCreatePage() {
  const params = useParams<{ resourceId: string }>()
  const resourceId = Number(params.resourceId)
  const navigate = useNavigate()
  const { showToast } = useToast()
  const startTimeGroupId = useId()

  const resourceQuery = useResourceDetail(resourceId)
  const createReservation = useCreateReservation()
  const registerWaitingQueue = useRegisterWaitingQueue()

  const [step, setStep] = useState<Step>('form')
  const [date, setDate] = useState(todayDateString())
  const [selectedSlot, setSelectedSlot] = useState<AvailabilitySlot | null>(null)
  const [duration, setDuration] = useState<number | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [conflictError, setConflictError] = useState<ApiError | null>(null)
  const [createdReservationId, setCreatedReservationId] = useState<number | null>(null)

  const availabilityQuery = useResourceAvailability(resourceId, date)

  // The previously selected slot belongs to whichever date it was fetched for; once the
  // user picks a different date it's no longer a valid selection.
  useEffect(() => {
    setSelectedSlot(null)
  }, [date])

  const policy = resourceQuery.data?.reservationPolicy ?? null

  const durationOptions = useMemo(() => {
    if (!policy) return []
    const options: number[] = []
    for (let value = policy.minDuration; value <= policy.maxDuration; value += policy.slotDuration) {
      options.push(value)
    }
    if (options.length === 0) options.push(policy.minDuration)
    return options
  }, [policy])

  const slotsByPeriod = useMemo(() => {
    const grouped: Record<DayPeriod, AvailabilitySlot[]> = { 오전: [], 오후: [], 저녁: [] }
    for (const slot of availabilityQuery.data?.slots ?? []) {
      grouped[periodOf(slot)].push(slot)
    }
    return grouped
  }, [availabilityQuery.data])

  const { startDate, endDate } = useMemo(() => {
    if (!selectedSlot || !duration) return { startDate: null, endDate: null }
    const start = new Date(selectedSlot.startAt)
    if (Number.isNaN(start.getTime())) return { startDate: null, endDate: null }
    const end = new Date(start.getTime() + duration * 60_000)
    return { startDate: start, endDate: end }
  }, [selectedSlot, duration])

  if (resourceQuery.isPending) {
    return <PageSpinner label="Resource 정보를 불러오는 중입니다..." />
  }

  if (resourceQuery.isError) {
    return <ErrorState error={resourceQuery.error} onRetry={() => resourceQuery.refetch()} />
  }

  const resource = resourceQuery.data

  if (resource.status !== 'ACTIVE' || !policy) {
    return (
      <ErrorState
        title="예약할 수 없는 Resource입니다"
        error={{ status: 409, message: '현재 예약이 불가능한 상태의 Resource입니다.', isNetworkError: false }}
      />
    )
  }

  function handleSubmitForm() {
    setFormError(null)
    if (!selectedSlot) {
      setFormError('예약 시작 시간을 선택해 주세요.')
      return
    }
    if (!duration) {
      setFormError('이용 시간을 선택해 주세요.')
      return
    }
    if (!startDate || startDate.getTime() <= Date.now()) {
      setFormError('현재 시각 이후의 시간만 예약할 수 있습니다.')
      return
    }
    setStep('review')
  }

  function handleConfirm() {
    if (!startDate || !duration) return
    createReservation.mutate(
      { resourceId, startAt: toBackendDateTime(startDate), duration },
      {
        onSuccess: (response) => {
          setCreatedReservationId(response.reservationId)
          setStep('success')
        },
        onError: (error) => {
          if (error.status === 409) {
            setConflictError(error)
            setStep('conflict')
          } else {
            showToast({ tone: 'danger', title: '예약 실패', description: getErrorMessage(error) })
            setStep('form')
          }
        },
      },
    )
  }

  function handleRegisterWaitingQueue() {
    if (!startDate || !endDate) return
    registerWaitingQueue.mutate(
      { resourceId, startAt: toBackendDateTime(startDate), endAt: toBackendDateTime(endDate) },
      {
        onSuccess: () => {
          showToast({ tone: 'success', title: '대기열에 등록되었습니다', description: '자리가 나면 실시간으로 알려드립니다.' })
          navigate('/waiting-queue')
        },
        onError: (error) => {
          showToast({ tone: 'danger', title: '대기열 등록 실패', description: getErrorMessage(error) })
        },
      },
    )
  }

  return (
    <div className="mx-auto max-w-xl">
      <Link
        to={`/resources/${resourceId}`}
        className="mb-4 inline-flex items-center gap-1.5 text-sm text-neutral-500 hover:text-neutral-700"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        {resource.name}(으)로 돌아가기
      </Link>

      <StepIndicator step={step} />

      <Card className="mt-4 p-6">
        <p className="text-xs font-medium text-neutral-500">{resourceTypeLabels[resource.resourceType]}</p>
        <h1 className="mt-0.5 text-lg font-semibold text-neutral-900">{resource.name} 예약</h1>

        {step === 'form' && (
          <div className="mt-6 flex flex-col gap-4">
            <FormField label="예약 날짜" required>
              {(id) => (
                <Input
                  id={id}
                  type="date"
                  min={todayDateString()}
                  value={date}
                  onChange={(event) => setDate(event.target.value)}
                />
              )}
            </FormField>

            <div className="flex flex-col gap-1.5">
              <span id={startTimeGroupId} className="text-sm font-medium text-neutral-800">
                시작 시간
                <span className="ml-0.5 text-danger-600" aria-hidden="true">
                  *
                </span>
              </span>

              <div
                role="group"
                aria-labelledby={startTimeGroupId}
                className="max-h-72 overflow-y-auto rounded-md border border-neutral-200 p-3"
              >
                {availabilityQuery.isPending && (
                  <div className="grid grid-cols-3 gap-2 sm:grid-cols-4">
                    {Array.from({ length: 12 }).map((_, index) => (
                      <Skeleton key={index} className="h-9 w-full" />
                    ))}
                  </div>
                )}

                {availabilityQuery.isError && (
                  <ErrorState error={availabilityQuery.error} onRetry={() => availabilityQuery.refetch()} />
                )}

                {availabilityQuery.data && availabilityQuery.data.slots.length === 0 && (
                  <EmptyState
                    icon={Clock3}
                    title="예약 가능한 시간이 없습니다"
                    description="다른 날짜를 선택해 주세요."
                  />
                )}

                {availabilityQuery.data && availabilityQuery.data.slots.length > 0 && (
                  <div className="flex flex-col gap-4">
                    {PERIOD_ORDER.filter((period) => slotsByPeriod[period].length > 0).map((period) => (
                      <div key={period}>
                        <p className="mb-2 text-xs font-medium text-neutral-500">{period}</p>
                        <div className="grid grid-cols-3 gap-2 sm:grid-cols-4">
                          {slotsByPeriod[period].map((slot) => {
                            const isSelected = selectedSlot?.startAt === slot.startAt
                            return (
                              <button
                                key={slot.startAt}
                                type="button"
                                disabled={!slot.available}
                                aria-pressed={isSelected}
                                onClick={() => setSelectedSlot(slot)}
                                className={cn(
                                  'rounded-md border px-2 py-1.5 text-sm font-medium transition-colors',
                                  isSelected && 'border-brand-600 bg-brand-600 text-white',
                                  !isSelected && slot.available && 'border-neutral-200 text-neutral-700 hover:border-brand-400 hover:bg-brand-50',
                                  !slot.available && 'cursor-not-allowed border-neutral-100 text-neutral-300',
                                )}
                              >
                                {formatTime(slot.startAt)}
                              </button>
                            )
                          })}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
              <p className="text-sm text-neutral-500">
                {policy.slotDuration}분 단위로 예약 가능한 시간 중에서 선택할 수 있습니다.
              </p>
            </div>

            <FormField
              label="이용 시간"
              required
              helpText={`최소 ${policy.minDuration}분, 최대 ${policy.maxDuration}분까지 예약할 수 있습니다.`}
            >
              {(id) => (
                <Select
                  id={id}
                  value={duration ?? ''}
                  onChange={(event) => setDuration(Number(event.target.value) || null)}
                >
                  <option value="" disabled>
                    이용 시간을 선택하세요
                  </option>
                  {durationOptions.map((option) => (
                    <option key={option} value={option}>
                      {formatDuration(option)}
                    </option>
                  ))}
                </Select>
              )}
            </FormField>

            {formError && (
              <p role="alert" className="rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
                {formError}
              </p>
            )}

            <Button size="lg" onClick={handleSubmitForm}>
              다음
            </Button>
          </div>
        )}

        {step === 'review' && startDate && endDate && duration && (
          <div className="mt-6 flex flex-col gap-4">
            <div className="flex flex-col gap-3 rounded-md bg-neutral-50 p-4 text-sm">
              <ReviewRow icon={CalendarClock} label="예약 시간" value={`${formatDateTime(toBackendDateTime(startDate))} - ${toBackendDateTime(endDate).slice(11, 16)}`} />
              <ReviewRow icon={Clock3} label="이용 시간" value={formatDuration(duration)} />
            </div>
            <p className="text-xs text-neutral-500">
              예약 확정 후 체크인 가능 시간은 시작 시각 5분 전부터입니다. 체크인하지 않으면 예약이 자동으로 취소될 수 있습니다.
            </p>
            <div className="flex gap-2">
              <Button variant="secondary" className="flex-1" onClick={() => setStep('form')}>
                수정
              </Button>
              <Button className="flex-1" onClick={handleConfirm} loading={createReservation.isPending}>
                예약 확정
              </Button>
            </div>
          </div>
        )}

        {step === 'conflict' && (
          <div className="mt-6 flex flex-col gap-4">
            <div className="rounded-md bg-warning-50 px-4 py-3 text-sm text-warning-700">
              {conflictError?.message ?? '해당 시간대는 이미 예약이 존재합니다.'}
            </div>
            <p className="text-sm text-neutral-600">
              대기열에 등록하면 해당 시간대의 예약이 취소되거나 만료될 때 순서대로 승급 기회를 실시간으로 받을 수
              있습니다.
            </p>
            <div className="flex gap-2">
              <Button variant="secondary" className="flex-1" onClick={() => setStep('form')}>
                다른 시간 선택
              </Button>
              <Button className="flex-1" onClick={handleRegisterWaitingQueue} loading={registerWaitingQueue.isPending}>
                대기열 등록하기
              </Button>
            </div>
          </div>
        )}

        {step === 'success' && createdReservationId && (
          <div className="mt-6 flex flex-col items-center gap-3 py-4 text-center">
            <div className="flex size-12 items-center justify-center rounded-full bg-success-50 text-success-600">
              <CheckCircle2 className="size-6" aria-hidden="true" />
            </div>
            <p className="text-sm font-medium text-neutral-900">예약이 완료되었습니다</p>
            <div className="flex w-full gap-2">
              <Link to="/reservations" className="flex-1">
                <Button variant="secondary" className="w-full">
                  내 예약 목록
                </Button>
              </Link>
              <Link to={`/reservations/${createdReservationId}`} className="flex-1">
                <Button className="w-full">예약 상세 보기</Button>
              </Link>
            </div>
          </div>
        )}
      </Card>
    </div>
  )
}

function StepIndicator({ step }: { step: Step }) {
  const items: { key: Step; label: string }[] = [
    { key: 'form', label: '시간 선택' },
    { key: 'review', label: '확인' },
    { key: 'success', label: '완료' },
  ]
  const activeIndex = step === 'conflict' ? 0 : items.findIndex((item) => item.key === step)

  return (
    <ol className="flex items-center gap-2 text-xs font-medium text-neutral-400">
      {items.map((item, index) => (
        <li key={item.key} className="flex items-center gap-2">
          <span
            className={
              index <= activeIndex
                ? 'flex size-5 items-center justify-center rounded-full bg-brand-600 text-white'
                : 'flex size-5 items-center justify-center rounded-full bg-neutral-200 text-neutral-500'
            }
          >
            {index + 1}
          </span>
          <span className={index <= activeIndex ? 'text-neutral-800' : ''}>{item.label}</span>
          {index < items.length - 1 && <span className="mx-1 h-px w-6 bg-neutral-200" aria-hidden="true" />}
        </li>
      ))}
    </ol>
  )
}

function ReviewRow({ icon: Icon, label, value }: { icon: typeof CalendarClock; label: string; value: string }) {
  return (
    <div className="flex items-center gap-3">
      <Icon className="size-4 text-neutral-400" aria-hidden="true" />
      <span className="text-neutral-500">{label}</span>
      <span className="ml-auto font-medium text-neutral-900">{value}</span>
    </div>
  )
}
