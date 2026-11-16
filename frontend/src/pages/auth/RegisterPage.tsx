import React, { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { ThemeTray } from '../../components/common/ThemeTray';
import {
  ShieldCheck,
  Lock,
  Mail,
  User,
  Building2,
  AlertCircle,
  Eye,
  EyeOff,
  CheckCircle2,
  Check,
  X,
  ArrowRight,
} from 'lucide-react';

export const RegisterPage: React.FC = () => {
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [organizationName, setOrganizationName] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);

  const { register } = useAuth();
  const { success } = useToast();
  const navigate = useNavigate();

  // Password rules
  const hasMinLength = password.length >= 8;
  const hasUpper = /[A-Z]/.test(password);
  const hasLower = /[a-z]/.test(password);
  const hasDigitOrSpecial = /[\d!@#$%^&*(),.?":{}|<>]/.test(password);
  const isPasswordValid = hasMinLength && hasUpper && hasLower && hasDigitOrSpecial;
  const doPasswordsMatch = password.length > 0 && password === confirmPassword;

  // Strength score
  const passedCount = [hasMinLength, hasUpper, hasLower, hasDigitOrSpecial].filter(Boolean).length;
  const strengthLabel = passedCount <= 1 ? 'Weak' : passedCount <= 3 ? 'Moderate' : 'Strong';
  const strengthColor =
    passedCount <= 1
      ? 'bg-rose-500'
      : passedCount <= 3
      ? 'bg-amber-500'
      : 'bg-emerald-500';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!fullName.trim() || !email.trim() || !organizationName.trim() || !password) {
      setErrorMessage('All fields are required.');
      return;
    }

    if (!isPasswordValid) {
      setErrorMessage('Please ensure your password satisfies all security requirements.');
      return;
    }

    if (!doPasswordsMatch) {
      setErrorMessage('Passwords do not match. Please verify your confirmation password.');
      return;
    }

    setErrorMessage(null);
    setIsLoading(true);

    try {
      await register({
        fullName: fullName.trim(),
        email: email.trim(),
        organizationName: organizationName.trim(),
        password,
      });

      success(
        'Account Created',
        `Welcome to StockGuard, ${fullName.trim()}! Your organization "${organizationName.trim()}" is ready.`
      );

      navigate('/app/dashboard');
    } catch (err: unknown) {
      if (err instanceof Error) {
        setErrorMessage(err.message);
      } else {
        setErrorMessage('Registration failed. Please check your details and retry.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-[#080c14] flex flex-col justify-center items-center px-4 py-12 relative overflow-hidden bg-grid-pattern transition-colors">
      {/* Top Controls: Return to Home & Theme Tray */}
      <div className="absolute top-6 left-6 right-6 flex items-center justify-between z-20">
        <Link
          to="/"
          className="text-xs font-medium text-slate-500 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-200 transition-colors flex items-center gap-1.5"
        >
          &larr; Back to Platform Overview
        </Link>
        <ThemeTray />
      </div>

      {/* Background Radial Glow */}
      <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[650px] h-[650px] bg-gradient-to-tr from-cyan-500/10 via-indigo-500/5 to-transparent rounded-full blur-3xl pointer-events-none -z-10" />

      {/* Brand Header */}
      <div className="sm:mx-auto sm:w-full sm:max-w-md text-center mb-6">
        <div className="inline-flex items-center justify-center p-3 rounded-2xl bg-gradient-to-br from-cyan-500 to-indigo-600 shadow-xl shadow-cyan-500/20 mb-3 ring-4 ring-cyan-500/10">
          <ShieldCheck className="w-8 h-8 text-white" />
        </div>
        <h2 className="text-2xl font-extrabold tracking-tight text-slate-900 dark:text-white">
          Create Organization Account
        </h2>
        <p className="mt-1.5 text-xs text-slate-500 dark:text-slate-400 max-w-sm mx-auto">
          Start your enterprise inventory control tenant. You will receive root Administrator privileges.
        </p>
      </div>

      {/* Registration Card */}
      <div className="sm:mx-auto sm:w-full sm:max-w-md">
        <div className="bg-white/90 dark:bg-[#0f172a]/90 backdrop-blur-xl py-8 px-6 sm:px-9 shadow-2xl rounded-2xl border border-slate-200/90 dark:border-slate-800/90 space-y-5">
          {errorMessage && (
            <div className="p-3.5 rounded-xl bg-rose-500/10 border border-rose-500/30 flex items-start gap-2.5 text-xs text-rose-600 dark:text-rose-400 animate-in fade-in">
              <AlertCircle className="w-4 h-4 shrink-0 mt-0.5 text-rose-500" />
              <span>{errorMessage}</span>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <Input
                label="Full Name"
                type="text"
                required
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                placeholder="e.g. Jane Doe"
                icon={<User className="w-4 h-4 text-slate-400" />}
                autoComplete="name"
              />
            </div>

            <div>
              <Input
                label="Work Email Address"
                type="email"
                required
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="jane@company.com"
                icon={<Mail className="w-4 h-4 text-slate-400" />}
                autoComplete="email"
              />
            </div>

            <div>
              <Input
                label="Organization / Company Name"
                type="text"
                required
                value={organizationName}
                onChange={(e) => setOrganizationName(e.target.value)}
                placeholder="e.g. Acme Logistics Global"
                icon={<Building2 className="w-4 h-4 text-slate-400" />}
              />
            </div>

            <div>
              <Input
                label="Account Password"
                type={showPassword ? 'text' : 'password'}
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="At least 8 characters"
                icon={<Lock className="w-4 h-4 text-slate-400" />}
                autoComplete="new-password"
                rightElement={
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 focus:outline-none transition-colors"
                    title={showPassword ? 'Hide password' : 'Show password'}
                  >
                    {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                }
              />

              {/* Password strength visualizer */}
              {password.length > 0 && (
                <div className="mt-2 space-y-2 text-[11px]">
                  <div className="flex items-center justify-between text-slate-500 dark:text-slate-400">
                    <span>Password Strength:</span>
                    <span className="font-semibold">{strengthLabel}</span>
                  </div>
                  <div className="h-1.5 w-full bg-slate-200 dark:bg-slate-800 rounded-full overflow-hidden flex">
                    <div
                      className={`h-full transition-all duration-300 ${strengthColor}`}
                      style={{ width: `${(passedCount / 4) * 100}%` }}
                    />
                  </div>
                  <div className="grid grid-cols-2 gap-1.5 text-slate-500 dark:text-slate-400">
                    <div className="flex items-center gap-1">
                      {hasMinLength ? (
                        <Check className="w-3.5 h-3.5 text-emerald-500" />
                      ) : (
                        <X className="w-3.5 h-3.5 text-slate-400" />
                      )}
                      <span>8+ characters</span>
                    </div>
                    <div className="flex items-center gap-1">
                      {hasUpper && hasLower ? (
                        <Check className="w-3.5 h-3.5 text-emerald-500" />
                      ) : (
                        <X className="w-3.5 h-3.5 text-slate-400" />
                      )}
                      <span>Upper & lowercase</span>
                    </div>
                    <div className="flex items-center gap-1">
                      {hasDigitOrSpecial ? (
                        <Check className="w-3.5 h-3.5 text-emerald-500" />
                      ) : (
                        <X className="w-3.5 h-3.5 text-slate-400" />
                      )}
                      <span>Number or symbol</span>
                    </div>
                    <div className="flex items-center gap-1">
                      {doPasswordsMatch ? (
                        <Check className="w-3.5 h-3.5 text-emerald-500" />
                      ) : (
                        <X className="w-3.5 h-3.5 text-slate-400" />
                      )}
                      <span>Passwords match</span>
                    </div>
                  </div>
                </div>
              )}
            </div>

            <div>
              <Input
                label="Confirm Password"
                type={showConfirmPassword ? 'text' : 'password'}
                required
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="Re-enter your password"
                icon={<Lock className="w-4 h-4 text-slate-400" />}
                autoComplete="new-password"
                rightElement={
                  <button
                    type="button"
                    onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                    className="text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 focus:outline-none transition-colors"
                    title={showConfirmPassword ? 'Hide password' : 'Show password'}
                  >
                    {showConfirmPassword ? (
                      <EyeOff className="w-4 h-4" />
                    ) : (
                      <Eye className="w-4 h-4" />
                    )}
                  </button>
                }
              />
            </div>

            {/* Role Notice */}
            <div className="p-3 rounded-lg bg-cyan-50/70 dark:bg-cyan-950/20 border border-cyan-200/80 dark:border-cyan-800/40 text-[11px] text-cyan-800 dark:text-cyan-300 flex items-start gap-2">
              <CheckCircle2 className="w-4 h-4 text-cyan-600 dark:text-cyan-400 shrink-0 mt-0.5" />
              <span>
                As founder, you are assigned the <strong>ADMIN</strong> role with complete permission to manage warehouses, products, orders, inventory reconciliation, and invite team members.
              </span>
            </div>

            <Button
              type="submit"
              variant="primary"
              className="w-full mt-2 cursor-pointer flex items-center justify-center gap-2"
              isLoading={isLoading}
              disabled={!isPasswordValid || !doPasswordsMatch || !fullName || !email || !organizationName}
            >
              <span>Create Organization & Account</span>
              <ArrowRight className="w-4 h-4" />
            </Button>
          </form>

          {/* Switch to login link */}
          <div className="pt-4 border-t border-slate-200 dark:border-slate-800 flex items-center justify-center text-xs text-slate-500 dark:text-slate-400">
            <span>Already have an account?</span>
            <Link
              to="/login"
              className="ml-1.5 font-semibold text-cyan-600 dark:text-cyan-400 hover:underline cursor-pointer"
            >
              Sign In to Console
            </Link>
          </div>
        </div>
      </div>

      <p className="mt-6 text-[11px] text-slate-500 text-center font-mono">
        PostgreSQL Row-Level Multi-Tenancy · BCrypt Password Hashing · HttpOnly Cookie Security
      </p>
    </div>
  );
};
