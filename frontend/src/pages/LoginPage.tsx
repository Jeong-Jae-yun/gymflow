import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/context/useAuth'
import { Button, FormField, Input } from '@/components/ui'
import { getErrorMessage } from '@/utils/getErrorMessage'
import type { LoginRequest } from '@/types'

interface LocationState {
  from?: { pathname: string }
}

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [serverError, setServerError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginRequest>({ mode: 'onBlur' })

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null)
    try {
      await login(values)
      const redirectTo = (location.state as LocationState | null)?.from?.pathname ?? '/'
      navigate(redirectTo, { replace: true })
    } catch (error) {
      setServerError(getErrorMessage(error, '이메일 또는 비밀번호가 올바르지 않습니다.'))
    }
  })

  return (
    <div>
      <h1 className="text-lg font-semibold text-neutral-900">로그인</h1>
      <p className="mt-1 text-sm text-neutral-500">GymFlow 계정으로 로그인하세요.</p>

      <form onSubmit={onSubmit} noValidate className="mt-6 flex flex-col gap-4">
        <FormField label="이메일" required error={errors.email?.message}>
          {(id, describedBy) => (
            <Input
              id={id}
              type="email"
              autoComplete="email"
              hasError={!!errors.email}
              aria-describedby={describedBy}
              placeholder="you@example.com"
              {...register('email', {
                required: '이메일을 입력해 주세요.',
                pattern: { value: /^[^\s@]+@[^\s@]+\.[^\s@]+$/, message: '올바른 이메일 형식이 아닙니다.' },
                maxLength: { value: 255, message: '이메일은 255자를 초과할 수 없습니다.' },
              })}
            />
          )}
        </FormField>

        <FormField label="비밀번호" required error={errors.password?.message}>
          {(id, describedBy) => (
            <Input
              id={id}
              type="password"
              autoComplete="current-password"
              hasError={!!errors.password}
              aria-describedby={describedBy}
              placeholder="8자 이상"
              {...register('password', {
                required: '비밀번호를 입력해 주세요.',
                minLength: { value: 8, message: '비밀번호는 8자 이상이어야 합니다.' },
                maxLength: { value: 64, message: '비밀번호는 64자를 초과할 수 없습니다.' },
              })}
            />
          )}
        </FormField>

        {serverError && (
          <p role="alert" className="rounded-md bg-danger-50 px-3 py-2 text-sm text-danger-700">
            {serverError}
          </p>
        )}

        <Button type="submit" className="mt-2 w-full" loading={isSubmitting}>
          로그인
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-neutral-500">
        계정이 없으신가요?{' '}
        <Link to="/signup" className="font-medium text-brand-600 hover:text-brand-700">
          회원가입
        </Link>
      </p>
    </div>
  )
}
