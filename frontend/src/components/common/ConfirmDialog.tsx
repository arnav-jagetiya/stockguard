import React from 'react';
import { AlertTriangle } from 'lucide-react';
import { Modal } from './Modal';
import { Button } from './Button';

interface ConfirmDialogProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: () => void;
  title: string;
  message: string;
  confirmLabel?: string;
  confirmText?: string;
  cancelLabel?: string;
  variant?: 'danger' | 'warning' | 'primary';
  isLoading?: boolean;
}

export const ConfirmDialog: React.FC<ConfirmDialogProps> = ({
  isOpen,
  onClose,
  onConfirm,
  title,
  message,
  confirmLabel,
  confirmText,
  cancelLabel = 'Cancel',
  variant = 'danger',
  isLoading = false,
}) => {
  const finalConfirmLabel = confirmText || confirmLabel || 'Confirm';
  return (
    <Modal isOpen={isOpen} onClose={onClose} title={title}>
      <div className="space-y-4">
        <div className="flex items-start gap-3">
          <div
            className={`p-2.5 rounded-full shrink-0 ${
              variant === 'danger'
                ? 'bg-rose-500/10 text-rose-500'
                : variant === 'warning'
                ? 'bg-amber-500/10 text-amber-500'
                : 'bg-[#E9EEE6] dark:bg-[#C6F36B]/15 text-[#456B1F] dark:text-[#C6F36B]'
            }`}
          >
            <AlertTriangle className="w-5 h-5" />
          </div>
          <p className="text-sm text-[#657369] dark:text-[#9AA99E] leading-relaxed pt-0.5">
            {message}
          </p>
        </div>

        <div className="flex justify-end gap-3 pt-3 border-t border-[#D9E1D7] dark:border-[#26362E]">
          <Button variant="secondary" onClick={onClose} disabled={isLoading}>
            {cancelLabel}
          </Button>
          <Button
            variant={variant === 'danger' ? 'danger' : variant === 'warning' ? 'secondary' : 'primary'}
            onClick={onConfirm}
            isLoading={isLoading}
          >
            {finalConfirmLabel}
          </Button>
        </div>
      </div>
    </Modal>
  );
};
