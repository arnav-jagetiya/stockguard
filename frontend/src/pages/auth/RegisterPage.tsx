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
    <div className="min-h-screen bg-[#F4F5EF] dark:bg-[#0B100F] flex flex-col justify-center items-center px-4 py-12 relative overflow-hidden transition-colors">
      {/* Top Controls: Return to Home & Theme Tray */}
      <div className="absolute top-6 left-6 right-6 flex items-center justify-between z-20">
        <Link
          to="/"
          className="text-xs font-medium text-[#657369] hover:text-[#18231D] dark:text-[#9AA99E] dark:hover:text-[#EEF3EA] transition-colors flex items-center gap-1.5"
        >
          &larr; Back to Platform Overview
        </Link>
        <ThemeTray />
      </div>

      {/* Background Radial Glow */}
      <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[650px] h-[650px] bg-gradient-to-tr from-[#C6F36B]/10 via-[#147C68]/5 to-transparent rounded-full blur-3xl pointer-events-none -z-10" />

      {/* Brand Header */}
      <div className="sm:mx-auto sm:w-full sm:max-w-md text-center mb-6">
        <div className="inline-flex items-center justify-center p-3 rounded-2xl bg-[#456B1F] dark:bg-[#C6F36B] shadow-xl shadow-[#456B1F]/20 mb-3 ring-4 ring-[#456B1F]/10 dark:ring-[#C6F36B]/20">
          <ShieldCheck className="w-8 h-8 text-white dark:text-[#0B100F]" />
        </div>
        <h2 className="text-2xl font-extrabold tracking-tight text-[#18231D] dark:text-[#EEF3EA]">
          Organization Registration
        </h2>
        <p className="mt-1.5 text-xs text-[#657369] dark:text-[#9AA99E] max-w-sm mx-auto">
          Public self-registration is closed for this deployment. Explore the platform via the live demo or sign in to your tenant.
        </p>
      </div>

      {/* Registration Notice & Alternatives Card */}
      <div className="sm:mx-auto sm:w-full sm:max-w-md">
        <div className="bg-white/95 dark:bg-[#111A17]/95 backdrop-blur-xl py-8 px-6 sm:px-9 shadow-2xl rounded-2xl border border-[#D9E1D7] dark:border-[#26362E] space-y-5">
          <div className="p-4 rounded-xl bg-amber-500/10 border border-amber-500/30 text-xs text-amber-900 dark:text-amber-200 space-y-2">
            <div className="flex items-center gap-2 font-bold">
              <AlertCircle className="w-4 h-4 text-amber-600 dark:text-amber-400 shrink-0" />
              <span>Public Self-Registration Disabled</span>
            </div>
            <p className="text-[11px] text-amber-800/90 dark:text-amber-300/90 leading-relaxed">
              New tenant provisioning requires explicit platform administrator onboarding. To experience StockGuard with real seeded distribution operations, access our public live demo.
            </p>
            <div className="pt-2 flex flex-col gap-2">
              <Link to="/login?demo=true">
                <Button className="w-full" size="sm" variant="primary">
                  Explore Live Demo (Acme Industrial)
                </Button>
              </Link>
              <Link to="/login">
                <Button className="w-full" size="sm" variant="outline">
                  Sign In to Existing Account
                </Button>
              </Link>
            </div>
          </div>
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
                icon={<User className="w-4 h-4 text-[#8A988E]" />}
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
                icon={<Mail className="w-4 h-4 text-[#8A988E]" />}
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
                icon={<Building2 className="w-4 h-4 text-[#8A988E]" />}
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
                icon={<Lock className="w-4 h-4 text-[#8A988E]" />}
                autoComplete="new-password"
                rightElement={
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] focus:outline-none transition-colors cursor-pointer"
                    title={showPassword ? 'Hide password' : 'Show password'}
                  >
                    {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                }
              />

              {/* Password strength visualizer */}
              {password.length > 0 && (
                <div className="mt-2 space-y-2 text-[11px]">
                  <div className="flex items-center justify-between text-[#657369] dark:text-[#9AA99E]">
                    <span>Password Strength:</span>
                    <span className="font-semibold">{strengthLabel}</span>
                  </div>
                  <div className="h-1.5 w-full bg-[#D9E1D7] dark:bg-[#26362E] rounded-full overflow-hidden flex">
                    <div
                      className={`h-full transition-all duration-300 ${strengthColor}`}
                      style={{ width: `${(passedCount / 4) * 100}%` }}
                    />
                  </div>
                  <div className="grid grid-cols-2 gap-1.5 text-[#657369] dark:text-[#9AA99E]">
                    <div className="flex items-center gap-1">
                      {hasMinLength ? (
                        <Check className="w-3.5 h-3.5 text-[#456B1F] dark:text-[#C6F36B]" />
                      ) : (
                        <X className="w-3.5 h-3.5 text-[#8A988E]" />
                      )}
                      <span>8+ characters</span>
                    </div>
                    <div className="flex items-center gap-1">
                      {hasUpper && hasLower ? (
                        <Check className="w-3.5 h-3.5 text-[#456B1F] dark:text-[#C6F36B]" />
                      ) : (
                        <X className="w-3.5 h-3.5 text-[#8A988E]" />
                      )}
                      <span>Upper & lowercase</span>
                    </div>
                    <div className="flex items-center gap-1">
                      {hasDigitOrSpecial ? (
                        <Check className="w-3.5 h-3.5 text-[#456B1F] dark:text-[#C6F36B]" />
                      ) : (
                        <X className="w-3.5 h-3.5 text-[#8A988E]" />
                      )}
                      <span>Number or symbol</span>
                    </div>
                    <div className="flex items-center gap-1">
                      {doPasswordsMatch ? (
                        <Check className="w-3.5 h-3.5 text-[#456B1F] dark:text-[#C6F36B]" />
                      ) : (
                        <X className="w-3.5 h-3.5 text-[#8A988E]" />
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
                icon={<Lock className="w-4 h-4 text-[#8A988E]" />}
                autoComplete="new-password"
                rightElement={
                  <button
                    type="button"
                    onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                    className="text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] focus:outline-none transition-colors cursor-pointer"
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
            <div className="p-3 rounded-lg bg-[#147C68]/10 border border-[#147C68]/30 text-[11px] text-[#147C68] dark:text-[#6CD8C0] flex items-start gap-2">
              <CheckCircle2 className="w-4 h-4 text-[#147C68] dark:text-[#6CD8C0] shrink-0 mt-0.5" />
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
          <div className="pt-4 border-t border-[#D9E1D7] dark:border-[#26362E] flex items-center justify-center text-xs text-[#657369] dark:text-[#9AA99E]">
            <span>Already have an account?</span>
            <Link
              to="/login"
              className="ml-1.5 font-semibold text-[#456B1F] dark:text-[#C6F36B] hover:underline cursor-pointer"
            >
              Sign In to Console
            </Link>
          </div>
        </div>
      </div>

      <p className="mt-6 text-[11px] text-[#657369] dark:text-[#9AA99E] text-center font-mono">
        PostgreSQL Row-Level Multi-Tenancy · BCrypt Password Hashing · HttpOnly Cookie Security
      </p>
    </div>
  );
};
